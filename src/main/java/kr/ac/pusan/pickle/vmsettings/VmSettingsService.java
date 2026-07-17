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
 * Per-VM settings (contract v0.8.0, product-spec §9). A code-side registry owns
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

    /** States in which no setting may change (contract 409 VM_INVALID_STATE). */
    private static final Set<VmStatus> UNCHANGEABLE_STATES =
            Set.of(VmStatus.DELETING, VmStatus.DELETED);

    /**
     * One registry entry. {@code auditAction} is uniform today
     * ({@code vm.setting_update}) but kept per-key for future divergence; an
     * M6 on-change hook (e.g. re-render the gateway on ssh_password_enabled)
     * would attach here.
     */
    private record VmSettingDef(String key, VmSettingValueType type, List<String> allowedValues,
            JsonNode defaultValue, GroupMemberRole requiredRole, String label, String description,
            String auditAction) {
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

        // 1) key existence + value type/allowed-values (collect all 422 errors).
        List<FieldValidationError> errors = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : settings.entrySet()) {
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
        for (String key : settings.keySet()) {
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
        for (Map.Entry<String, JsonNode> entry : settings.entrySet()) {
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
            case STRING -> value.isString();
        };
        if (valid) {
            return java.util.Optional.empty();
        }
        String message = switch (def.type()) {
            case BOOLEAN -> "true 또는 false여야 합니다.";
            case ENUM -> String.join(", ", def.allowedValues()) + " 중 하나여야 합니다.";
            case INTEGER -> "정수여야 합니다.";
            case STRING -> "문자열이어야 합니다.";
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
        return Collections.unmodifiableMap(map);
    }
}
