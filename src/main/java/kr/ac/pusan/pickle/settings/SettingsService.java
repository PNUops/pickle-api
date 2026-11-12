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
import kr.ac.pusan.pickle.llm.LlmUsageRetentionPolicy;
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

    public static final String GPU_UNATTACHED_REVIEW_HOURS = "gpu_unattached_review_hours";
    public static final String GPU_LOW_UTIL_WINDOW_HOURS = "gpu_low_util_window_hours";
    public static final String GPU_LOW_UTIL_THRESHOLD_PERCENT = "gpu_low_util_threshold_percent";
    public static final String GPU_LOW_UTIL_SNOOZE_HOURS = "gpu_low_util_snooze_hours";
    public static final String GPU_LEASE_NOTICE_HOURS = "gpu_lease_notice_hours";

    public static final String ALLOWED_ROOT_DOMAINS = "allowed_root_domains";
    public static final String PLATFORM_SUBDOMAINS_PER_VM = "platform_subdomains_per_vm";
    public static final String PLATFORM_SUBDOMAIN_RESERVE_DAYS = "platform_subdomain_reserve_days";
    public static final String RESERVED_SUBDOMAINS = "reserved_subdomains";
    public static final String DOMAIN_RENEWAL_DAYS = "domain_renewal_days";
    public static final String DOMAIN_RENEWAL_NOTICE_DAYS = "domain_renewal_notice_days";
    public static final String PROFANITY_SUBDOMAINS = "profanity_subdomains";
    public static final String VCPU_OVERCOMMIT_WARN = "vcpu_overcommit_warn";
    /**
     * How many VMs of many-person requests may be in creation at once on each
     * node. The limit is per node, not across all of them: a node at the limit
     * takes no more of these VMs while another node may still take them. VMs
     * of single-person approvals are not counted. Read with a code default of
     * 4 when the key is absent; editable once seeded.
     *
     * <p>Capped at 8: every VM is a full clone, so the node's disk is the
     * bottleneck.
     * Measured on the test node, eight at once took about 75% longer per VM
     * than four (median 151 s against 86 s) for about 14% more throughput, and
     * pushed iowait to 77%, load that lands on the disks of VMs already
     * running there.
     */
    public static final String BULK_PROVISION_CONCURRENCY = "bulk_provision_concurrency";
    public static final String MEMORY_USAGE_WARN = "memory_usage_warn";
    public static final String IP_QUARANTINE_HOURS = "ip_quarantine_hours";
    public static final String VM_DELETE_GRACE_HOURS = "vm_delete_grace_hours";
    public static final String SSH_GATEWAY_ENABLED = "ssh_gateway_enabled";
    // V16 seeds the first two; vm_expiry_autostop_enabled arrives with V18.
    public static final String VM_EXPIRY_NOTICE_DAYS = "vm_expiry_notice_days";
    public static final String NOTIFICATION_RETENTION_DAYS = "notification_retention_days";
    /** LLM 원본 사용량 이벤트 보관 기간(일). 0=무기한, 그 외는 90일 이상. */
    public static final String LLM_USAGE_RETENTION_DAYS = "llm_usage_retention_days";
    public static final String VM_EXPIRY_AUTOSTOP_ENABLED = "vm_expiry_autostop_enabled";
    // 점검 모드·공지 배너·문의처 (V43, GET /meta/status).
    public static final String MAINTENANCE_MODE = "maintenance_mode";
    public static final String MAINTENANCE_MESSAGE = "maintenance_message";
    public static final String BANNER_MESSAGE = "banner_message";
    public static final String CONTACT_EMAIL = "contact_email";
    /** Web-terminal global kill switch (V46, default false). */
    public static final String WEB_TERMINAL_ENABLED = "web_terminal_enabled";
    // Relay port forwarding (V60, contract v0.27.0).
    /** Port-forwarding kill switch (default false: creation refused, existing kept). */
    public static final String PORT_FORWARDING_ENABLED = "port_forwarding_enabled";
    public static final String CUSTOM_DOMAIN_LIMIT_PER_HOUR = "custom_domain_limit_per_hour";
    public static final String PORT_FORWARD_ALLOC_LIMIT_PER_HOUR =
            "port_forward_alloc_limit_per_hour";
    public static final String PORT_FORWARD_BAND_ALERT_PERCENT =
            "port_forward_band_alert_percent";
    public static final String PORT_FORWARD_SUSPEND_CONNS_PER_MIN =
            "port_forward_suspend_conns_per_min";
    public static final String PORT_FORWARD_SUSPEND_MBYTES_PER_MIN =
            "port_forward_suspend_mbytes_per_min";

    /** Hostname-safe entry: lowercase dot-separated labels, ≤63 chars total. */
    private static final Pattern HOSTNAME_SAFE = Pattern.compile(
            "(?=.{1,63}$)[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");
    /**
     * Floor for the IP quarantine window, in hours. A released address may
     * still be carried in a forwarding relay's persisted snapshot, which the
     * relay agent re-applies on boot for as long as that snapshot is
     * considered usable (24 hours). Quarantining for less would let the
     * address be reassigned while stale forwarding rules still point at it,
     * delivering public traffic to a different tenant's VM, so the setting
     * must never be editable below the agent's replay window.
     */
    private static final int MIN_IP_QUARANTINE_HOURS = 24;
    private static final int MAX_LIST_ENTRIES = 500;
    private static final int MAX_EXPIRY_STAGES = 5;
    /** Free-text operator message cap (banner/maintenance notice). */
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final int MAX_EMAIL_LENGTH = 254;
    /** Pragmatic email check (empty allowed); full RFC compliance is not the goal. */
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    /** One editable key: declared type, validator and release-owned help. */
    private record Editable(SettingValueType type,
            Function<JsonNode, List<FieldValidationError>> validator, String description) {
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

    /** Reads the stored value at call time, refusing missing or invalid configuration. */
    public JsonNode requiredValid(String key) {
        JsonNode value = read(key);
        Editable rule = EDITABLE.get(key);
        if (value == null || rule == null || !rule.validator().apply(value).isEmpty()) {
            throw new IllegalStateException("GPU 설정을 확인해 주세요: " + key);
        }
        return value;
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
                    "리소스를 찾을 수 없어요", "해당 설정 키가 없거나 바꿀 수 없는 항목이에요.");
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
                    new FieldValidationError("value", "설정 값은 null일 수 없어요.")));
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
        return new SettingView(key, value, type, descriptionFor(key, description),
                editable != null, updatedAt);
    }

    /** Known help belongs to the release; other keys retain their stored prose. */
    public static String descriptionFor(String key, String storedDescription) {
        Editable editable = EDITABLE.get(key);
        if (editable != null) {
            return editable.description();
        }
        return storedDescription != null ? storedDescription : "";
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
        map.put(GPU_UNATTACHED_REVIEW_HOURS, new Editable(SettingValueType.INTEGER, intInRange(1, 8760),
                "GPU를 VM에 연결하지 않은 상태가 이 시간 이상 지속되면 검토 대상으로 올려요(시간). 자동으로 회수하지 않아요."));
        map.put(GPU_LOW_UTIL_WINDOW_HOURS, new Editable(SettingValueType.INTEGER, intInRange(1, 8760),
                "GPU 저사용 검토에 쓸 최근 이용률 기간이에요(시간). 유효한 표본이 부족하면 저사용으로 판단하지 않아요."));
        map.put(GPU_LOW_UTIL_SNOOZE_HOURS, new Editable(SettingValueType.INTEGER, intInRange(1, 8760),
                "관리자가 GPU 유지를 결정한 뒤 다시 검토할 때까지 기다리는 시간이에요(시간)."));
        map.put(GPU_LOW_UTIL_THRESHOLD_PERCENT, new Editable(SettingValueType.NUMBER, value ->
                value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() >= 0 && value.asDouble() <= 100
                ? List.of() : List.of(new FieldValidationError("value", "0 이상 100 이하의 숫자를 입력해 주세요.")),
                "판단 기간의 평균 GPU 이용률이 이 값 미만이면 검토 대상으로 올려요(%). 실제 회수는 관리자가 결정해요."));
        map.put(GPU_LEASE_NOTICE_HOURS, new Editable(SettingValueType.JSON, value -> {
            if (!value.isArray() || value.size() > 5) {
                return List.of(new FieldValidationError("value", "알림 시점은 최대 5개의 시간을 담은 배열로 입력해 주세요."));
            }
            Set<Integer> seen = new LinkedHashSet<>();
            for (JsonNode item : value) {
                if (!item.isIntegralNumber() || !item.canConvertToInt() || item.asInt() < 1 || !seen.add(item.asInt())) {
                    return List.of(new FieldValidationError("value", "중복 없는 양의 정수 시간을 입력해 주세요."));
                }
            }
            return List.of();
        },
                "GPU 임대 종료를 미리 알릴 시점이에요(시간 전). 승인한 임대 기간보다 짧은 단계만 적용하며 만료 시각은 바꾸지 않아요."));
        map.put(BULK_PROVISION_CONCURRENCY, new Editable(SettingValueType.INTEGER, intInRange(1, 8),
                "여러 명 몫으로 승인된 VM 신청의 노드별 동시 생성 수예요(1~8). 상한에 닿은 노드는 건너뛰며 일반 노드가 모두 상한이면 GPU 노드도 사용할 수 있어요. 한 명 몫 신청으로 만드는 VM은 세지 않아요."));
        map.put(VCPU_OVERCOMMIT_WARN, new Editable(SettingValueType.NUMBER,
                numberInRangeExclusiveMin(0, 10),
                "승인 화면에서 할당 vCPU와 물리 스레드의 비율을 경고하는 기준이에요."));
        map.put(MEMORY_USAGE_WARN, new Editable(SettingValueType.NUMBER,
                numberInRangeExclusiveMin(0, 10),
                "승인 화면에서 할당 메모리와 물리 메모리의 비율을 경고하는 기준이에요."));
        map.put(IP_QUARANTINE_HOURS, new Editable(SettingValueType.INTEGER,
                intInRange(MIN_IP_QUARANTINE_HOURS, 720),
                "회수한 IP를 다시 할당하지 않는 격리 시간이에요(시간). 릴레이 스냅샷의 부팅 시 재적용 기간인 24시간보다 짧게 설정할 수 없어요."));
        map.put(PLATFORM_SUBDOMAINS_PER_VM, new Editable(SettingValueType.INTEGER,
                intInRange(0, 100),
                "VM 하나에 연결할 수 있는 플랫폼 서브도메인 수예요. 예약 중인 이름과 커스텀 도메인은 제외하며 0이면 새 플랫폼 서브도메인 연결을 막아요."));
        map.put(PLATFORM_SUBDOMAIN_RESERVE_DAYS, new Editable(SettingValueType.INTEGER,
                intInRange(0, 365),
                "해제한 플랫폼 이름을 예약해 두는 기간이에요(일). VM에 연결했던 이름은 같은 VM에서 다시 연결할 수 있고, 별도로 발급한 도메인은 같은 워크스페이스에서 되살릴 수 있어요. 기한이 지나면 이름을 정리할 대상이 되며 0이면 해제 뒤 바로 정리 대상이 돼요."));
        // No zero: a deadline of "today" would reclaim a name the moment it is
        // issued, and the button that renews it would never be reachable.
        map.put(DOMAIN_RENEWAL_DAYS, new Editable(SettingValueType.INTEGER,
                intInRange(1, 3650),
                "VM과 별도로 발급한 도메인의 사용 기간이에요(일). 발급이나 연장 시점부터 계산하며 남은 기간에 더하지 않아요."));
        map.put(DOMAIN_RENEWAL_NOTICE_DAYS, new Editable(SettingValueType.JSON, expiryStages(),
                "VM과 별도로 발급한 도메인의 사용 기한을 미리 알릴 시점이에요(D-일). 중복 없이 1~90일을 최대 5개 지정해요."));
        map.put(VM_DELETE_GRACE_HOURS, new Editable(SettingValueType.INTEGER,
                intInRange(1, 2160),
                "본인 삭제를 접수한 뒤 파기까지 기다릴 시간이에요(시간). 관리자만 이 유예 기간에 취소할 수 있어요."));
        map.put(SSH_GATEWAY_ENABLED, new Editable(SettingValueType.BOOLEAN, bool(),
                "SSH 게이트웨이의 전역 스위치예요. false면 새 게이트웨이 접속을 차단하며 기존 SSH 연결은 종료하지 않아요."));
        map.put(WEB_TERMINAL_ENABLED, new Editable(SettingValueType.BOOLEAN, bool(),
                "웹 터미널의 전역 스위치예요. false면 새 티켓 발급과 재교환을 거절해요. 열린 세션도 다음 정상 권한 재확인에서 닫혀요. 기본 확인 주기는 60초이며 통신 실패로 늦어질 수 있어요. VM별 차단과 접근 권한도 별도로 확인하며 VM 설정으로 전역 차단을 우회할 수 없어요."));
        map.put(PORT_FORWARDING_ENABLED, new Editable(SettingValueType.BOOLEAN, bool(),
                "포트포워딩(릴레이 공개 포트)의 신규 생성 스위치예요. false면 새 매핑 생성을 차단하고 기존 매핑은 유지해요."));
        map.put(CUSTOM_DOMAIN_LIMIT_PER_HOUR, new Editable(SettingValueType.INTEGER,
                intInRange(1, 1000),
                "사용자별 커스텀 도메인 연결 요청의 시간당 상한이에요. 플랫폼 서브도메인 연결은 이 제한에 포함하지 않아요."));
        map.put(PORT_FORWARD_ALLOC_LIMIT_PER_HOUR, new Editable(SettingValueType.INTEGER,
                intInRange(1, 1000),
                "사용자별 포트포워딩 생성 허용 횟수예요(시간당)."));
        map.put(PORT_FORWARD_BAND_ALERT_PERCENT, new Editable(SettingValueType.INTEGER,
                intInRange(1, 100),
                "릴레이 공개 포트 대역 사용률의 경고 기준이에요(%). 도달하면 시스템 관리자에게 알림을 접수해요."));
        map.put(PORT_FORWARD_SUSPEND_CONNS_PER_MIN, new Editable(SettingValueType.INTEGER,
                intInRange(1, 100_000_000),
                "매핑별 분당 새 연결 수의 자동 정지 임계값이에요. 초과하면 ACTIVE 매핑을 SUSPENDED로 바꿔요."));
        map.put(PORT_FORWARD_SUSPEND_MBYTES_PER_MIN, new Editable(SettingValueType.INTEGER,
                intInRange(1, 10_000_000),
                "매핑별 분당 전송량(MiB)의 자동 정지 임계값이에요. 초과하면 ACTIVE 매핑을 SUSPENDED로 바꿔요."));
        map.put(VM_EXPIRY_AUTOSTOP_ENABLED, new Editable(SettingValueType.BOOLEAN, bool(),
                "사용 기간(end_date, 포함)이 지난 VM의 매시간 자동 종료 요청을 정하는 스위치예요. 끄면 자동 종료를 요청하지 않고 예고 알림만 처리해요."));
        map.put(ALLOWED_ROOT_DOMAINS, new Editable(SettingValueType.JSON, hostnameArray(),
                "발급을 허용할 루트 도메인 목록이에요."));
        map.put(RESERVED_SUBDOMAINS, new Editable(SettingValueType.JSON, hostnameArray(),
                "신청할 수 없는 예약 서브도메인 목록이에요."));
        map.put(PROFANITY_SUBDOMAINS, new Editable(SettingValueType.JSON, hostnameArray(),
                "욕설이나 사칭을 막는 서브도메인 금칙어 목록이에요. 시스템 관리자가 추가할 수 있어요."));
        map.put(VM_EXPIRY_NOTICE_DAYS, new Editable(SettingValueType.JSON, expiryStages(),
                "VM 사용 종료 사전 알림 시점이에요(D-일). 중복 없이 1~90일을 최대 5개 지정하며 D-1 알림은 중요하게 표시해요."));
        map.put(NOTIFICATION_RETENTION_DAYS, new Editable(SettingValueType.INTEGER,
                intInRange(30, 3650),
                "오래된 알림을 정리하는 기준이에요(30~3650일). 발송 대기나 처리 중인 알림은 지우지 않고 전달 결과 기록도 남겨요."));
        map.put(LLM_USAGE_RETENTION_DAYS, new Editable(SettingValueType.INTEGER,
                zeroOrIntInRange(LlmUsageRetentionPolicy.MINIMUM_RETENTION_DAYS, 3650),
                "LLM 원본 사용량 이벤트 보관 기간이에요(일). 0은 무기한이고, 기간을 정할 때는 90~3650일로 입력해요. 일별 집계는 남지만 원본이 필요한 지연과 시간대 통계의 조회 기간은 줄어들어요. 삭제된 원본은 기간을 늘려도 돌아오지 않아요."));
        map.put(MAINTENANCE_MODE, new Editable(SettingValueType.BOOLEAN, bool(),
                "점검 모드예요. true면 기관과 시스템의 관리자 계층 외 요청을 503(MAINTENANCE_MODE)으로 거절하며 로그인 등 예외 경로는 유지해요. 상태 캐시의 유효시간은 15초이며 이미 처리 중인 요청은 중단하지 않아요."));
        map.put(MAINTENANCE_MESSAGE, new Editable(SettingValueType.STRING,
                stringMaxLength(MAX_MESSAGE_LENGTH),
                "점검 모드 안내예요. 비우면 기본 안내를 사용해요."));
        map.put(BANNER_MESSAGE, new Editable(SettingValueType.STRING,
                stringMaxLength(MAX_MESSAGE_LENGTH),
                "점검 모드와 별개인 콘솔 상단 공지 배너예요. 비우면 배너를 표시하지 않아요."));
        map.put(CONTACT_EMAIL, new Editable(SettingValueType.STRING, emailOrEmpty(),
                "운영 문의 이메일이에요. 콘솔 푸터와 점검 화면, 오류 화면에 표시하며 비우면 숨겨요."));
        return Map.copyOf(map);
    }

    private static Function<JsonNode, List<FieldValidationError>> bool() {
        return value -> value.isBoolean() ? List.of()
                : List.of(new FieldValidationError("value", "true나 false를 입력해 주세요."));
    }

    /** A string (empty allowed) up to {@code max} chars. */
    private static Function<JsonNode, List<FieldValidationError>> stringMaxLength(int max) {
        return value -> {
            if (!value.isString()) {
                return List.of(new FieldValidationError("value", "문자열로 입력해 주세요."));
            }
            if (value.asString().length() > max) {
                return List.of(new FieldValidationError("value",
                        max + "자 이하의 문자열로 입력해 주세요."));
            }
            return List.of();
        };
    }

    /** An email string, or an empty string to clear it. */
    private static Function<JsonNode, List<FieldValidationError>> emailOrEmpty() {
        return value -> {
            if (!value.isString()) {
                return List.of(new FieldValidationError("value", "문자열로 입력해 주세요."));
            }
            String email = value.asString();
            if (email.isEmpty()) {
                return List.of();
            }
            if (email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches()) {
                return List.of(new FieldValidationError("value", "올바른 이메일 주소나 빈 문자열을 입력해 주세요."));
            }
            return List.of();
        };
    }

    private static Function<JsonNode, List<FieldValidationError>> intInRange(int min, int max) {
        return value -> {
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                return List.of(new FieldValidationError("value", "정수를 입력해 주세요."));
            }
            int v = value.asInt();
            if (v < min || v > max) {
                return List.of(new FieldValidationError("value",
                        min + " 이상 " + max + " 이하의 정수를 입력해 주세요."));
            }
            return List.of();
        };
    }

    /**
     * Zero, or an integer in range. For a retention that is off by default and
     * has a floor when it is on: a plain range would make turning it back off
     * impossible, and a range starting at zero would allow the values between
     * zero and the floor, which are the dangerous ones.
     */
    private static Function<JsonNode, List<FieldValidationError>> zeroOrIntInRange(int min,
            int max) {
        return value -> {
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                return List.of(new FieldValidationError("value", "정수를 입력해 주세요."));
            }
            int v = value.asInt();
            if (v == 0 || (v >= min && v <= max)) {
                return List.of();
            }
            return List.of(new FieldValidationError("value",
                    "0(무기한) 또는 " + min + " 이상 " + max + " 이하의 정수를 입력해 주세요."));
        };
    }

    private static Function<JsonNode, List<FieldValidationError>> numberInRangeExclusiveMin(
            double minExclusive, double maxInclusive) {
        return value -> {
            if (!value.isNumber()) {
                return List.of(new FieldValidationError("value", "숫자를 입력해 주세요."));
            }
            double v = value.asDouble();
            if (!(v > minExclusive) || v > maxInclusive) {
                return List.of(new FieldValidationError("value",
                        minExclusive + " 초과 " + maxInclusive + " 이하의 숫자를 입력해 주세요."));
            }
            return List.of();
        };
    }

    private static Function<JsonNode, List<FieldValidationError>> hostnameArray() {
        return value -> {
            if (!value.isArray()) {
                return List.of(new FieldValidationError("value", "문자열 배열로 입력해 주세요."));
            }
            if (value.size() > MAX_LIST_ENTRIES) {
                return List.of(new FieldValidationError("value",
                        "항목은 최대 " + MAX_LIST_ENTRIES + "개까지 지정할 수 있어요."));
            }
            List<FieldValidationError> errors = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < value.size(); i++) {
                JsonNode item = value.get(i);
                if (!item.isString() || !HOSTNAME_SAFE.matcher(item.asString()).matches()) {
                    errors.add(new FieldValidationError("value[" + i + "]",
                            "소문자와 숫자, 하이픈, 점으로 된 63자 이하의 호스트명을 입력해 주세요."));
                } else if (!seen.add(item.asString())) {
                    errors.add(new FieldValidationError("value[" + i + "]", "같은 항목을 중복해서 지정할 수 없어요."));
                }
            }
            return errors;
        };
    }

    private static Function<JsonNode, List<FieldValidationError>> expiryStages() {
        return value -> {
            if (!value.isArray()) {
                return List.of(new FieldValidationError("value", "정수 배열로 입력해 주세요."));
            }
            if (value.isEmpty() || value.size() > MAX_EXPIRY_STAGES) {
                return List.of(new FieldValidationError("value",
                        "알림 단계는 1개 이상 " + MAX_EXPIRY_STAGES + "개 이하로 지정해 주세요."));
            }
            List<FieldValidationError> errors = new ArrayList<>();
            Set<Integer> seen = new LinkedHashSet<>();
            for (int i = 0; i < value.size(); i++) {
                JsonNode item = value.get(i);
                if (!item.isIntegralNumber() || item.asInt() < 1 || item.asInt() > 90) {
                    errors.add(new FieldValidationError("value[" + i + "]",
                            "1 이상 90 이하의 정수를 입력해 주세요."));
                } else if (!seen.add(item.asInt())) {
                    errors.add(new FieldValidationError("value[" + i + "]", "같은 일수를 중복해서 지정할 수 없어요."));
                }
            }
            return errors;
        };
    }
}
