package kr.ac.pusan.pickle.settings;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.settings.dto.SettingView;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The runtime-tunable {@code settings} store (V1 baseline): typed getters for
 * feature code plus the SYS_ADMIN editor surface (contract v0.5.0
 * {@code listSettings}/{@code updateSetting}).
 *
 * <p>Editing is gated by a <b>static whitelist</b> with per-key type/range
 * validators. Keys present in the DB but not whitelisted are read-only
 * ({@code editable=false}, PUT answers 404 exactly like an unknown key so the
 * two are indistinguishable). Values are full replacements — no merge.</p>
 */
@Service
public class SettingsService {

    public static final String ALLOWED_ROOT_DOMAINS = "allowed_root_domains";
    public static final String RESERVED_SUBDOMAINS = "reserved_subdomains";
    public static final String PROFANITY_SUBDOMAINS = "profanity_subdomains";
    public static final String VCPU_OVERCOMMIT_WARN = "vcpu_overcommit_warn";
    public static final String MEMORY_USAGE_WARN = "memory_usage_warn";
    public static final String IP_QUARANTINE_HOURS = "ip_quarantine_hours";
    public static final String VM_DELETE_GRACE_HOURS = "vm_delete_grace_hours";
    public static final String ADMIN_DELETE_MIN_NOTICE_DAYS = "vm_admin_delete_min_notice_days";
    public static final String SSH_GATEWAY_ENABLED = "ssh_gateway_enabled";
    // V16 seeds the first two; vm_expiry_autostop_enabled arrives with V18.
    public static final String VM_EXPIRY_NOTICE_DAYS = "vm_expiry_notice_days";
    public static final String NOTIFICATION_RETENTION_DAYS = "notification_retention_days";
    public static final String VM_EXPIRY_AUTOSTOP_ENABLED = "vm_expiry_autostop_enabled";
    // 점검 모드·공지 배너·문의처 (V43, GET /meta/status).
    public static final String MAINTENANCE_MODE = "maintenance_mode";
    public static final String MAINTENANCE_MESSAGE = "maintenance_message";
    public static final String BANNER_MESSAGE = "banner_message";
    public static final String CONTACT_EMAIL = "contact_email";
    /** Web-terminal global kill switch (V46, default false). */
    public static final String WEB_TERMINAL_ENABLED = "web_terminal_enabled";

    /** Hostname-safe entry: lowercase dot-separated labels, ≤63 chars total. */
    private static final Pattern HOSTNAME_SAFE = Pattern.compile(
            "(?=.{1,63}$)[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");
    private static final int MAX_LIST_ENTRIES = 500;
    private static final int MAX_EXPIRY_STAGES = 5;
    /** Free-text operator message cap (banner/maintenance notice). */
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final int MAX_EMAIL_LENGTH = 254;
    /** Pragmatic email check (empty allowed); full RFC compliance is not the goal. */
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    /** One whitelisted (operator-editable) key: declared type + validator. */
    private record Editable(SettingValueType type,
            Function<JsonNode, List<FieldValidationError>> validator) {
    }

    private static final Map<String, Editable> EDITABLE = buildWhitelist();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public SettingsService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    // ── typed getters (feature code) ───────────────────────────────────────

    /** The setting as a list of strings; empty when the key is missing. */
    public List<String> stringList(String key) {
        JsonNode node = read(key);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(node.size());
        node.forEach(item -> values.add(item.asString()));
        return List.copyOf(values);
    }

    /** The setting as a list of ints; empty when the key is missing. */
    public List<Integer> intList(String key) {
        JsonNode node = read(key);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Integer> values = new ArrayList<>(node.size());
        node.forEach(item -> values.add(item.asInt()));
        return List.copyOf(values);
    }

    /** The setting as a decimal number, or {@code fallback} when missing. */
    public double decimal(String key, double fallback) {
        JsonNode node = read(key);
        return node != null && node.isNumber() ? node.asDouble() : fallback;
    }

    /** The setting as an integer, or {@code fallback} when missing. */
    public int integer(String key, int fallback) {
        JsonNode node = read(key);
        return node != null && node.isNumber() ? node.asInt() : fallback;
    }

    /** The setting as a boolean, or {@code fallback} when missing/non-boolean. */
    public boolean bool(String key, boolean fallback) {
        JsonNode node = read(key);
        return node != null && node.isBoolean() ? node.asBoolean() : fallback;
    }

    /**
     * The setting as a string, or {@code null} when missing, non-string, or
     * blank. Blank collapses to {@code null} so an empty (unset) message/email
     * reads uniformly regardless of whether it was seeded as {@code ""} or never
     * set.
     */
    public String string(String key) {
        JsonNode node = read(key);
        if (node == null || !node.isString()) {
            return null;
        }
        String value = node.asString();
        return value.isBlank() ? null : value;
    }

    // ── SYS_ADMIN editor (contract listSettings / updateSetting) ───────────

    /** Every settings row, whitelisted ones marked editable. Key-ordered. */
    public List<SettingView> list() {
        return jdbcTemplate.query("""
                select key, value, description, updated_at from settings order by key
                """, (rs, rowNum) -> toView(rs.getString("key"), rs.getString("value"),
                rs.getString("description"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant()));
    }

    /**
     * Full-replace of a whitelisted key's value. Unknown, non-whitelisted, and
     * not-yet-seeded keys all answer 404 (existence stays private); type/range
     * violations answer 422 with field errors. Audited as
     * {@code setting.update} with the old and new values after commit.
     */
    @Transactional
    public SettingView update(AuthenticatedUser actor, String key, JsonNode value, String ip) {
        Editable editable = EDITABLE.get(key);
        JsonNode old = read(key);
        if (editable == null || old == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "리소스를 찾을 수 없습니다", "해당 설정 키가 존재하지 않거나 수정할 수 없습니다.");
        }
        JsonNode normalized = validateAndNormalize(key, editable, value);
        jdbcTemplate.update("update settings set value = ?::jsonb, updated_at = now() where key = ?",
                objectMapper.writeValueAsString(normalized), key);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.SETTING_UPDATE,
                "setting", null, Map.of("key", key, "old", old, "new", normalized), ip);
        return jdbcTemplate.queryForObject("""
                select key, value, description, updated_at from settings where key = ?
                """, (rs, rowNum) -> toView(rs.getString("key"), rs.getString("value"),
                rs.getString("description"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant()), key);
    }

    private JsonNode validateAndNormalize(String key, Editable editable, JsonNode value) {
        if (value == null || value.isNull()) {
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("value", "값은 비어 있을 수 없습니다.")));
        }
        List<FieldValidationError> errors = editable.validator().apply(value);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
        if (VM_EXPIRY_NOTICE_DAYS.equals(key)) {
            // The stages are consumed largest-first (D-30 → D-7 → …): store
            // them normalized descending so consumers never have to sort.
            List<Integer> stages = new ArrayList<>();
            value.forEach(item -> stages.add(item.asInt()));
            stages.sort(Comparator.reverseOrder());
            return objectMapper.valueToTree(stages);
        }
        return value;
    }

    private SettingView toView(String key, String valueJson, String description, Instant updatedAt) {
        JsonNode value = objectMapper.readTree(valueJson);
        Editable editable = EDITABLE.get(key);
        SettingValueType type = editable != null ? editable.type() : inferType(value);
        return new SettingView(key, value, type, description != null ? description : "",
                editable != null, updatedAt);
    }

    private static SettingValueType inferType(JsonNode value) {
        if (value.isBoolean()) {
            return SettingValueType.BOOLEAN;
        }
        if (value.isIntegralNumber()) {
            return SettingValueType.INTEGER;
        }
        if (value.isNumber()) {
            return SettingValueType.NUMBER;
        }
        if (value.isString()) {
            return SettingValueType.STRING;
        }
        return SettingValueType.JSON;
    }

    private JsonNode read(String key) {
        String json = jdbcTemplate.query("select value from settings where key = ?",
                rs -> rs.next() ? rs.getString(1) : null, key);
        return json == null ? null : objectMapper.readTree(json);
    }

    // ── whitelist validators ───────────────────────────────────────────────

    private static Map<String, Editable> buildWhitelist() {
        Map<String, Editable> map = new LinkedHashMap<>();
        map.put(VCPU_OVERCOMMIT_WARN, new Editable(SettingValueType.NUMBER,
                numberInRangeExclusiveMin(0, 10)));
        map.put(MEMORY_USAGE_WARN, new Editable(SettingValueType.NUMBER,
                numberInRangeExclusiveMin(0, 10)));
        map.put(IP_QUARANTINE_HOURS, new Editable(SettingValueType.INTEGER,
                intInRange(0, 720)));
        map.put(VM_DELETE_GRACE_HOURS, new Editable(SettingValueType.INTEGER,
                intInRange(1, 2160)));
        map.put(ADMIN_DELETE_MIN_NOTICE_DAYS, new Editable(SettingValueType.INTEGER,
                intInRange(0, 90)));
        map.put(SSH_GATEWAY_ENABLED, new Editable(SettingValueType.BOOLEAN, bool()));
        map.put(WEB_TERMINAL_ENABLED, new Editable(SettingValueType.BOOLEAN, bool()));
        map.put(VM_EXPIRY_AUTOSTOP_ENABLED, new Editable(SettingValueType.BOOLEAN, bool()));
        map.put(ALLOWED_ROOT_DOMAINS, new Editable(SettingValueType.JSON, hostnameArray()));
        map.put(RESERVED_SUBDOMAINS, new Editable(SettingValueType.JSON, hostnameArray()));
        map.put(PROFANITY_SUBDOMAINS, new Editable(SettingValueType.JSON, hostnameArray()));
        map.put(VM_EXPIRY_NOTICE_DAYS, new Editable(SettingValueType.JSON, expiryStages()));
        map.put(NOTIFICATION_RETENTION_DAYS, new Editable(SettingValueType.INTEGER,
                intInRange(30, 3650)));
        map.put(MAINTENANCE_MODE, new Editable(SettingValueType.BOOLEAN, bool()));
        map.put(MAINTENANCE_MESSAGE, new Editable(SettingValueType.STRING,
                stringMaxLength(MAX_MESSAGE_LENGTH)));
        map.put(BANNER_MESSAGE, new Editable(SettingValueType.STRING,
                stringMaxLength(MAX_MESSAGE_LENGTH)));
        map.put(CONTACT_EMAIL, new Editable(SettingValueType.STRING, emailOrEmpty()));
        return Map.copyOf(map);
    }

    private static Function<JsonNode, List<FieldValidationError>> bool() {
        return value -> value.isBoolean() ? List.of()
                : List.of(new FieldValidationError("value", "true 또는 false여야 합니다."));
    }

    /** A string (empty allowed) up to {@code max} chars. */
    private static Function<JsonNode, List<FieldValidationError>> stringMaxLength(int max) {
        return value -> {
            if (!value.isString()) {
                return List.of(new FieldValidationError("value", "문자열이어야 합니다."));
            }
            if (value.asString().length() > max) {
                return List.of(new FieldValidationError("value",
                        max + "자 이하의 문자열이어야 합니다."));
            }
            return List.of();
        };
    }

    /** An email string, or an empty string to clear it. */
    private static Function<JsonNode, List<FieldValidationError>> emailOrEmpty() {
        return value -> {
            if (!value.isString()) {
                return List.of(new FieldValidationError("value", "문자열이어야 합니다."));
            }
            String email = value.asString();
            if (email.isEmpty()) {
                return List.of();
            }
            if (email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches()) {
                return List.of(new FieldValidationError("value", "올바른 이메일 주소여야 합니다."));
            }
            return List.of();
        };
    }

    private static Function<JsonNode, List<FieldValidationError>> intInRange(int min, int max) {
        return value -> {
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                return List.of(new FieldValidationError("value", "정수여야 합니다."));
            }
            int v = value.asInt();
            if (v < min || v > max) {
                return List.of(new FieldValidationError("value",
                        min + " 이상 " + max + " 이하의 정수여야 합니다."));
            }
            return List.of();
        };
    }

    private static Function<JsonNode, List<FieldValidationError>> numberInRangeExclusiveMin(
            double minExclusive, double maxInclusive) {
        return value -> {
            if (!value.isNumber()) {
                return List.of(new FieldValidationError("value", "숫자여야 합니다."));
            }
            double v = value.asDouble();
            if (!(v > minExclusive) || v > maxInclusive) {
                return List.of(new FieldValidationError("value",
                        minExclusive + " 초과 " + maxInclusive + " 이하의 숫자여야 합니다."));
            }
            return List.of();
        };
    }

    private static Function<JsonNode, List<FieldValidationError>> hostnameArray() {
        return value -> {
            if (!value.isArray()) {
                return List.of(new FieldValidationError("value", "문자열 배열이어야 합니다."));
            }
            if (value.size() > MAX_LIST_ENTRIES) {
                return List.of(new FieldValidationError("value",
                        "항목은 최대 " + MAX_LIST_ENTRIES + "개까지 허용됩니다."));
            }
            List<FieldValidationError> errors = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < value.size(); i++) {
                JsonNode item = value.get(i);
                if (!item.isString() || !HOSTNAME_SAFE.matcher(item.asString()).matches()) {
                    errors.add(new FieldValidationError("value[" + i + "]",
                            "소문자 영숫자·하이픈·점으로 된 63자 이하의 호스트명이어야 합니다."));
                } else if (!seen.add(item.asString())) {
                    errors.add(new FieldValidationError("value[" + i + "]", "중복된 항목입니다."));
                }
            }
            return errors;
        };
    }

    private static Function<JsonNode, List<FieldValidationError>> expiryStages() {
        return value -> {
            if (!value.isArray()) {
                return List.of(new FieldValidationError("value", "정수 배열이어야 합니다."));
            }
            if (value.isEmpty() || value.size() > MAX_EXPIRY_STAGES) {
                return List.of(new FieldValidationError("value",
                        "알림 단계는 1개 이상 " + MAX_EXPIRY_STAGES + "개 이하여야 합니다."));
            }
            List<FieldValidationError> errors = new ArrayList<>();
            Set<Integer> seen = new LinkedHashSet<>();
            for (int i = 0; i < value.size(); i++) {
                JsonNode item = value.get(i);
                if (!item.isIntegralNumber() || item.asInt() < 1 || item.asInt() > 90) {
                    errors.add(new FieldValidationError("value[" + i + "]",
                            "1 이상 90 이하의 정수여야 합니다."));
                } else if (!seen.add(item.asInt())) {
                    errors.add(new FieldValidationError("value[" + i + "]", "중복된 일수입니다."));
                }
            }
            return errors;
        };
    }
}
