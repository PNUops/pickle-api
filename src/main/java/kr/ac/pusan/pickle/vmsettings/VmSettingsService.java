package kr.ac.pusan.pickle.vmsettings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.vmsettings.dto.VmSettingView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-VM settings (contract v0.8.0). A code-side registry owns
 * each key's type, default, and required role; the {@code vm_settings} table
 * holds only overrides. Two surfaces: the console GET/PATCH (EDITOR+; per-key
 * role for changes) and the enforcement getters used by feature code (the SSH
 * route's {@code ssh_password_enabled}, the password reveal's
 * {@code password_reveal_min_role}).
 */
@Service
public class VmSettingsService {

    public static final String SSH_PASSWORD_ENABLED = "ssh_password_enabled";
    public static final String PASSWORD_REVEAL_MIN_ROLE = "password_reveal_min_role";
    public static final String DELETION_PROTECTION = "deletion_protection";
    public static final String STOP_PROTECTION = "stop_protection";
    public static final String DISPLAY_NAME = "display_name";

    /** Contract cap for {@code display_name} (STRING); empty string clears it. */
    private static final int DISPLAY_NAME_MAX_LENGTH = 100;

    /** States in which no setting may change (contract 409 VM_INVALID_STATE). */
    private static final Set<VmStatus> UNCHANGEABLE_STATES =
            Set.of(VmStatus.DELETING, VmStatus.DELETED);

    /**
     * One registry entry. {@code auditAction} is uniform today
     * ({@code vm.setting_update}) but kept per-key for future divergence.
     * Settings are pure pickle-side state: enforcement is pull-based (feature
     * code reads the getters). The one eager-sync hook we ever had —
     * deletion_protection mirrored to the PVE flag — was removed when the
     * hypervisor flag became always-on platform state; avoid reintroducing
     * request-time external syncs here.
     */
    private record VmSettingDef(String key, VmSettingValueType type, List<String> allowedValues,
            JsonNode defaultValue, GroupMemberRole requiredRole, String label, String description,
            String auditAction, int maxLength) {

        /** {@code maxLength} 0 means "no cap" (only STRING keys use it today). */
        VmSettingDef(String key, VmSettingValueType type, List<String> allowedValues,
                JsonNode defaultValue, GroupMemberRole requiredRole, String label, String description,
                String auditAction) {
            this(key, type, allowedValues, defaultValue, requiredRole, label, description,
                    auditAction, 0);
        }
    }

    private final VmSettingRepository settingRepository;
    private final VmRepository vmRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Map<String, VmSettingDef> registry;

    public VmSettingsService(VmSettingRepository settingRepository, VmRepository vmRepository,
            GroupMemberRepository groupMemberRepository, UserRepository userRepository,
            AuditService auditService, ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.vmRepository = vmRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.registry = buildRegistry(objectMapper);
    }

    // ── enforcement getters (feature code; row absent = registry default) ──────

    /** Boolean setting, e.g. {@code ssh_password_enabled} for the SSH route. */
    @Transactional(readOnly = true)
    public boolean bool(long vmId, String key) {
        VmSettingDef def = requireDef(key);
        JsonNode value = currentValue(vmId, def);
        return value.isBoolean() ? value.asBoolean() : def.defaultValue().asBoolean();
    }

    /** Group-role setting, e.g. {@code password_reveal_min_role} for reveal. */
    @Transactional(readOnly = true)
    public GroupMemberRole role(long vmId, String key) {
        VmSettingDef def = requireDef(key);
        JsonNode value = currentValue(vmId, def);
        String raw = value.isString() ? value.asString() : def.defaultValue().asString();
        return GroupMemberRole.valueOf(raw);
    }

    /** STRING setting (e.g. {@code display_name}); blank/absent → null. */
    @Transactional(readOnly = true)
    public String string(long vmId, String key) {
        VmSettingDef def = requireDef(key);
        JsonNode value = currentValue(vmId, def);
        if (!value.isString()) {
            return null;
        }
        String raw = value.asString();
        return raw.isBlank() ? null : raw;
    }

    /**
     * System write for the SYS_ADMIN force-delete override: persists
     * {@code deletion_protection=false} so the destroy pipeline's logical gate
     * lets the VM through — including the sweeper-recovered run after a crash.
     * Deliberately skips the role/state gates of {@link #patch} (the caller
     * runs inside the force-delete transaction, which has already moved the VM
     * to DELETING) and leaves auditing to the caller's force-delete audit.
     */
    @Transactional
    public void disableDeletionProtection(long vmId, Long actorId) {
        Instant now = Instant.now();
        if (settingRepository.forceValueFalse(vmId, DELETION_PROTECTION, actorId, now) == 0) {
            settingRepository.save(new VmSetting(vmId, DELETION_PROTECTION,
                    objectMapper.writeValueAsString(Boolean.FALSE), actorId, now));
        }
    }

    /**
     * System write at approval time: seeds the requester-chosen display name
     * (request form, v0.22.0) so the VM carries its name from the first list
     * render. Deliberately skips the role/state gates of {@link #patch} (the
     * caller runs inside the approval transaction, before the VM is usable)
     * and leaves auditing to the caller's request.approve audit entry — this
     * is an initial value, not an actor-driven change.
     *
     * @return the value actually stored, or {@code null} when the requested
     *     name sanitized away to nothing and no row was written — the caller
     *     audits what was stored, never the raw request value.
     */
    @Transactional
    public String initializeDisplayName(long vmId, String displayName, Long requesterId) {
        // Own the invariants here instead of trusting the request DTO: the
        // patch() path caps length via the registry, and control/format chars
        // (incl. zero-width/RTL overrides) would only serve display spoofing.
        String sanitized = sanitizeDisplayName(displayName);
        if (sanitized.isEmpty()) {
            return null;
        }
        if (sanitized.length() > DISPLAY_NAME_MAX_LENGTH) {
            sanitized = sanitized.substring(0, DISPLAY_NAME_MAX_LENGTH);
        }
        settingRepository.save(new VmSetting(vmId, DISPLAY_NAME,
                objectMapper.writeValueAsString(sanitized), requesterId, Instant.now()));
        return sanitized;
    }

    /**
     * Display-name normalizer shared by the approval seed and the console
     * PATCH: control/format chars (incl. zero-width and RTL overrides) only
     * ever serve display spoofing, so both entry points strip them — otherwise
     * the seed path's invariant would be trivially bypassed by a later PATCH.
     */
    private static String sanitizeDisplayName(String value) {
        return value.replaceAll("[\\p{Cc}\\p{Cf}]", "").strip();
    }

    /**
     * Batch variant of {@code string(vmId, DISPLAY_NAME)} for list views —
     * one query for all VMs (avoids N+1). Absent/blank names are simply not in
     * the returned map.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> displayNames(java.util.Collection<Long> vmIds) {
        if (vmIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new java.util.HashMap<>();
        for (VmSetting row : settingRepository.findByKeyAndVmIdIn(DISPLAY_NAME, vmIds)) {
            JsonNode value = parse(row.getValue());
            if (value.isString() && !value.asString().isBlank()) {
                names.put(row.getVmId(), value.asString());
            }
        }
        return names;
    }

    // ── console surface (contract getVmSettings / updateVmSettings) ────────────

    /** EDITOR+ only; non-member answers 404 (existence masking). */
    @Transactional(readOnly = true)
    public List<VmSettingView> get(AuthenticatedUser actor, long vmId) {
        Vm vm = vmRepository.findById(vmId).orElseThrow(VmSettingsService::vmNotFound);
        GroupMemberRole actorRole = memberRole(vm, actor);
        if (!actorRole.atLeast(GroupMemberRole.EDITOR)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.GROUP_ROLE_INSUFFICIENT,
                    "VM 설정에 접근할 권한이 없습니다", "그룹의 EDITOR 이상만 VM 설정을 볼 수 있습니다.");
        }
        return buildViews(vm, actorRole);
    }

    /**
     * Applies a partial map atomically. Unknown key / type / empty map → 422;
     * any key whose required role exceeds the actor's → 403; DELETING/DELETED
     * VM → 409. Every change is audited (old→new). Returns the full list.
     */
    @Transactional
    public List<VmSettingView> patch(AuthenticatedUser actor, long vmId,
            Map<String, JsonNode> settings, String ip) {
        Vm vm = vmRepository.findById(vmId).orElseThrow(VmSettingsService::vmNotFound);
        GroupMemberRole actorRole = memberRole(vm, actor);
        if (settings == null || settings.isEmpty()) {
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("settings", "변경할 설정을 1개 이상 지정해 주세요.")));
        }
        if (UNCHANGEABLE_STATES.contains(vm.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                    "현재 상태에서는 수행할 수 없는 작업입니다",
                    "삭제 중이거나 삭제된 VM의 설정은 변경할 수 없습니다.");
        }
        // 0) normalize before anything looks at the values, so validation, the
        //    persisted row and the audit entry all see the same text.
        Map<String, JsonNode> incoming = sanitizeDisplayNameEntry(settings);

        // 1) key existence + value type/allowed-values (collect all 422 errors).
        List<FieldValidationError> errors = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : incoming.entrySet()) {
            VmSettingDef def = registry.get(entry.getKey());
            if (def == null) {
                errors.add(new FieldValidationError("settings." + entry.getKey(),
                        "알 수 없는 설정 키입니다."));
            } else {
                validateValue(def, entry.getValue()).ifPresent(errors::add);
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
        // 2) per-key required role (first offending key → 403).
        for (String key : incoming.keySet()) {
            VmSettingDef def = registry.get(key);
            if (!actorRole.atLeast(def.requiredRole())) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.GROUP_ROLE_INSUFFICIENT,
                        "설정을 변경할 권한이 없습니다",
                        "`" + key + "` 설정은 그룹의 " + def.requiredRole()
                                + " 이상만 변경할 수 있습니다.");
            }
        }
        // 3) apply + audit (atomic within the tx).
        Instant now = Instant.now();
        for (Map.Entry<String, JsonNode> entry : incoming.entrySet()) {
            VmSettingDef def = registry.get(entry.getKey());
            JsonNode newValue = entry.getValue();
            String newJson = objectMapper.writeValueAsString(newValue);
            VmSetting row = settingRepository.findByVmIdAndKey(vmId, def.key()).orElse(null);
            JsonNode oldValue = row != null ? parse(row.getValue()) : def.defaultValue();
            if (row == null) {
                settingRepository.save(new VmSetting(vmId, def.key(), newJson, actor.id(), now));
            } else {
                row.apply(newJson, actor.id(), now);
                settingRepository.save(row);
            }
            auditService.recordAfterCommit(actor.id(), actor.role().name(), def.auditAction(),
                    "vm", vmId, Map.of("key", def.key(), "old", oldValue, "new", newValue), ip);
        }
        return buildViews(vm, actorRole);
    }

    // ── internals ──────────────────────────────────────────────────────────

    /**
     * Returns the patch map with {@code display_name} normalized the same way
     * the approval seed normalizes it. Applied before validation so the
     * registry's length cap measures the visible text, and before persist so
     * the stored row and the audit entry carry the sanitized value. Any other
     * map is returned untouched.
     */
    private Map<String, JsonNode> sanitizeDisplayNameEntry(Map<String, JsonNode> settings) {
        JsonNode raw = settings.get(DISPLAY_NAME);
        if (raw == null || !raw.isString()) {
            return settings;
        }
        String sanitized = sanitizeDisplayName(raw.asString());
        if (sanitized.equals(raw.asString())) {
            return settings;
        }
        Map<String, JsonNode> normalized = new LinkedHashMap<>(settings);
        normalized.put(DISPLAY_NAME, objectMapper.valueToTree(sanitized));
        return normalized;
    }

    private List<VmSettingView> buildViews(Vm vm, GroupMemberRole actorRole) {
        Map<String, VmSetting> rows = settingRepository.findByVmId(vm.getId()).stream()
                .collect(Collectors.toMap(VmSetting::getKey, Function.identity()));
        Map<Long, String> updaterNames = updaterNames(rows.values());
        boolean vmChangeable = !UNCHANGEABLE_STATES.contains(vm.getStatus());
        List<VmSettingView> views = new ArrayList<>(registry.size());
        for (VmSettingDef def : registry.values()) {
            VmSetting row = rows.get(def.key());
            JsonNode value = row != null ? parse(row.getValue()) : def.defaultValue();
            boolean editable = vmChangeable && actorRole.atLeast(def.requiredRole());
            String updatedByName = row != null && row.getUpdatedBy() != null
                    ? updaterNames.get(row.getUpdatedBy()) : null;
            Instant updatedAt = row != null ? row.getUpdatedAt() : null;
            views.add(new VmSettingView(def.key(), value, def.type(), def.allowedValues(),
                    def.defaultValue(), def.label(), def.description(), def.requiredRole(),
                    editable, updatedByName, updatedAt));
        }
        return views;
    }

    private Map<Long, String> updaterNames(Iterable<VmSetting> rows) {
        Set<Long> ids = new java.util.HashSet<>();
        for (VmSetting row : rows) {
            if (row.getUpdatedBy() != null) {
                ids.add(row.getUpdatedBy());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
    }

    private java.util.Optional<FieldValidationError> validateValue(VmSettingDef def, JsonNode value) {
        String field = "settings." + def.key();
        boolean valid = switch (def.type()) {
            case BOOLEAN -> value.isBoolean();
            case ENUM -> value.isString() && def.allowedValues().contains(value.asString());
            case INTEGER -> value.isIntegralNumber();
            // STRING accepts any string incl. "" (empty = clear); length capped.
            case STRING -> value.isString()
                    && (def.maxLength() == 0 || value.asString().length() <= def.maxLength());
        };
        if (valid) {
            return java.util.Optional.empty();
        }
        String message = switch (def.type()) {
            case BOOLEAN -> "true 또는 false여야 합니다.";
            case ENUM -> String.join(", ", def.allowedValues()) + " 중 하나여야 합니다.";
            case INTEGER -> "정수여야 합니다.";
            case STRING -> value.isString() && def.maxLength() > 0
                    ? "최대 " + def.maxLength() + "자까지 입력할 수 있습니다."
                    : "문자열이어야 합니다.";
        };
        return java.util.Optional.of(new FieldValidationError(field, message));
    }

    private JsonNode currentValue(long vmId, VmSettingDef def) {
        return settingRepository.findByVmIdAndKey(vmId, def.key())
                .map(row -> parse(row.getValue()))
                .orElse(def.defaultValue());
    }

    private JsonNode parse(String json) {
        return objectMapper.readTree(json);
    }

    private VmSettingDef requireDef(String key) {
        VmSettingDef def = registry.get(key);
        if (def == null) {
            throw new IllegalArgumentException("unknown vm setting key: " + key);
        }
        return def;
    }

    private GroupMemberRole memberRole(Vm vm, AuthenticatedUser actor) {
        return groupMemberRepository.findByGroupIdAndUserId(vm.getGroupId(), actor.id())
                .map(GroupMember::getRole)
                .orElseThrow(VmSettingsService::vmNotFound);
    }

    private static ApiException vmNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 VM이 존재하지 않습니다.");
    }

    private static Map<String, VmSettingDef> buildRegistry(ObjectMapper mapper) {
        Map<String, VmSettingDef> map = new LinkedHashMap<>();
        map.put(SSH_PASSWORD_ENABLED, new VmSettingDef(SSH_PASSWORD_ENABLED,
                VmSettingValueType.BOOLEAN, null, mapper.valueToTree(Boolean.FALSE),
                GroupMemberRole.EDITOR, "비밀번호 SSH 허용",
                "SSH 게이트웨이에서 비밀번호 접속을 허용합니다. 켜면 접속자 개인을 식별할 수 없습니다.",
                AuditService.VM_SETTING_UPDATE));
        map.put(PASSWORD_REVEAL_MIN_ROLE, new VmSettingDef(PASSWORD_REVEAL_MIN_ROLE,
                VmSettingValueType.ENUM, List.of("MEMBER", "EDITOR", "OWNER"),
                mapper.valueToTree("MEMBER"), GroupMemberRole.OWNER, "비밀번호 열람 최소 역할",
                "VM 비밀번호(= sudo 자격)를 열람할 수 있는 최소 그룹 역할입니다.",
                AuditService.VM_SETTING_UPDATE));
        map.put(DELETION_PROTECTION, new VmSettingDef(DELETION_PROTECTION,
                VmSettingValueType.BOOLEAN, null, mapper.valueToTree(Boolean.FALSE),
                GroupMemberRole.OWNER, "삭제 보호",
                "켜면 본인·관리자 일반·강제 삭제 접수가 모두 거부됩니다(논리적 잠금 — 파기 "
                        + "시점에도 재확인). 이와 별개로 모든 VM은 Proxmox 네이티브 protection "
                        + "플래그로 하이퍼바이저 수준에서 상시 보호되며, 실제 파기 직전에만 "
                        + "플랫폼이 해제합니다. 삭제하려면 먼저 이 설정을 해제하세요.",
                AuditService.VM_SETTING_UPDATE));
        map.put(STOP_PROTECTION, new VmSettingDef(STOP_PROTECTION,
                VmSettingValueType.BOOLEAN, null, mapper.valueToTree(Boolean.FALSE),
                GroupMemberRole.OWNER, "중지 보호",
                "켜면 종료·재부팅·강제 종료를 EDITOR 이상만 수행할 수 있습니다(시작은 제한 없음). "
                        + "한계: sudo 가능한 구성원은 게스트 내부에서 여전히 종료할 수 있습니다.",
                AuditService.VM_SETTING_UPDATE));
        map.put(DISPLAY_NAME, new VmSettingDef(DISPLAY_NAME,
                VmSettingValueType.STRING, null, mapper.nullNode(),
                GroupMemberRole.EDITOR, "표시명",
                "콘솔 목록·상세에 이름 대신 표시할 별칭입니다(최대 " + DISPLAY_NAME_MAX_LENGTH
                        + "자, 빈 문자열로 해제). 호스트네임·슬러그·Proxmox 이름은 바뀌지 않습니다.",
                AuditService.VM_SETTING_UPDATE, DISPLAY_NAME_MAX_LENGTH));
        return Collections.unmodifiableMap(map);
    }
}
