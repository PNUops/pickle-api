package kr.ac.pusan.pickle.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.CreateOrgRequest;
import kr.ac.pusan.pickle.admin.dto.OrgDetailResponse;
import kr.ac.pusan.pickle.admin.dto.UpdateOrgRequest;
import kr.ac.pusan.pickle.admin.dto.UpdateUserAdminRequest;
import kr.ac.pusan.pickle.admin.dto.GrantOrgRoleRequest;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.dto.UserSummaryResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserOrgRoleService;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organisation and user administration (contract tag {@code admin}). Most of it
 * is SYS_ADMIN-only and the role gate for those sits on the controller, but the
 * organisation-role grant and revoke are open to ORG_ADMIN as well, and
 * <b>their organisation-level check is here, not on the controller</b>: the gate
 * sees only the effective role, which an account carries everywhere it holds
 * any role at all.
 */
@Service
public class AdminService {

    private final OrgRepository orgRepository;
    private final UserOrgRoleService userOrgRoleService;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public AdminService(OrgRepository orgRepository, UserRepository userRepository,
            UserOrgRoleService userOrgRoleService, AuditService auditService) {
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.userOrgRoleService = userOrgRoleService;
        this.auditService = auditService;
    }

    /** Contract {@code listAdminOrgs} (v0.20.0): every org, all statuses + hidden. */
    @Transactional(readOnly = true)
    public java.util.List<OrgDetailResponse> listOrgs() {
        return orgRepository.findAll(org.springframework.data.domain.Sort.by("id")).stream()
                .map(OrgDetailResponse::from)
                .toList();
    }

    @Transactional
    public OrgDetailResponse createOrg(AuthenticatedUser actor, CreateOrgRequest request, String ip) {
        // No duplicate check: the org slug carried the only uniqueness constraint
        // this table ever had, and V78 dropped it. Two orgs may share a name.
        Org org = orgRepository.save(new Org(request.name().strip(),
                normalize(request.description())));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.ORG_CREATE,
                "org", org.getPublicId(), Map.of("name", org.getName()), ip);
        return OrgDetailResponse.from(org);
    }

    @Transactional
    public OrgDetailResponse updateOrg(AuthenticatedUser actor, UUID orgId, UpdateOrgRequest request, String ip) {
        Org org = orgRepository.findByPublicId(orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                        "리소스를 찾을 수 없습니다", "해당 기관이 존재하지 않습니다."));
        if (request.isEmpty()) {
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("name", "수정할 값을 하나 이상 보내 주세요.")));
        }
        if (request.isNameSet()) {
            if (request.getName() == null || request.getName().isBlank()) {
                throw ApiException.validationFailed(List.of(
                        new FieldValidationError("name", "기관 이름은 비울 수 없습니다.")));
            }
            org.setName(request.getName().strip());
        }
        if (request.isDescriptionSet()) {
            org.setDescription(normalize(request.getDescription()));
        }
        if (request.isStatusSet()) {
            if (request.getStatus() == null) {
                throw ApiException.validationFailed(List.of(
                        new FieldValidationError("status", "status는 ACTIVE 또는 DISABLED여야 합니다.")));
            }
            org.setStatus(request.getStatus());
        }
        if (request.isHiddenSet()) {
            if (request.getHidden() == null) {
                throw ApiException.validationFailed(List.of(
                        new FieldValidationError("hidden", "hidden은 true 또는 false여야 합니다.")));
            }
            org.setHidden(request.getHidden());
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.ORG_UPDATE,
                "org", org.getPublicId(), Map.of("name", org.getName(), "status", org.getStatus().name(),
                        "hidden", org.isHidden()), ip);
        return OrgDetailResponse.from(org);
    }

    /**
     * Role/managed-org change. A role change bumps {@code token_version} so
     * outstanding access tokens of the target user become invalid immediately.
     */
    @Transactional
    public UserSummaryResponse updateUser(AuthenticatedUser actor, UUID userId,
            UpdateUserAdminRequest request, String ip) {
        User user = userRepository.findByPublicId(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                        "사용자를 찾을 수 없습니다", "해당 ID의 사용자가 존재하지 않습니다."));
        if (request.isEmpty()) {
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("role", "수정할 값을 하나 이상 보내 주세요.")));
        }

        UserRole previousRole = user.getRole();
        UserRole targetRole = request.role().toUserRole();
        List<Long> previousOrgIds = userOrgRoleService.scopeOf(user.getId()).orgIds();
        if (!previousOrgIds.isEmpty()) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("role",
                    "기관 역할이 남아 있습니다. 기관별 역할을 모두 회수한 뒤 전역 역할을 변경해 주세요.")));
        }

        if (targetRole != previousRole) {
            user.setRole(targetRole);
            user.bumpTokenVersion();
        }

        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.USER_ROLE_UPDATE,
                "user", user.getPublicId(),
                Map.of("previousRole", previousRole.name(), "role", user.getRole().name(),
                        "orgId", orgPublicIdsOrNone(previousOrgIds)), ip);
        return UserSummaryResponse.from(user);
    }

    /**
     * Gives an account a role in one organisation, or changes the one it has
     * there. Additive: what it holds in other organisations is untouched.
     *
     * <p>The {@code @PreAuthorize} gate cannot decide this on its own. It sees
     * the effective role, and an account that administers any organisation
     * carries {@code ORG_ADMIN} everywhere — so the organisation-level check
     * belongs here. A caller who does not administer the target organisation
     * gets the same 404 an unknown organisation gets, because whether an
     * organisation exists is not theirs to learn.
     */
    @Transactional
    public UserSummaryResponse grantOrgRole(AuthenticatedUser actor, UUID userId, UUID orgId,
            GrantOrgRoleRequest request, String ip) {
        if (!request.role().isOrgTier()) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("role",
                    "기관 역할은 ORG_ADMIN, ORG_MANAGER, ORG_VIEWER 중 하나여야 합니다.")));
        }
        Long targetOrgId = requireGrantableOrg(actor, orgId);
        User user = requireGrantableUser(actor, userId);
        UserRole previousRole = user.getRole();
        userOrgRoleService.grant(user, targetOrgId, request.role());
        bumpIfRoleChanged(user, previousRole);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.USER_ROLE_UPDATE,
                "user", user.getPublicId(),
                Map.of("previousRole", previousRole.name(), "role", user.getRole().name(),
                        "grantedOrgId", String.valueOf(orgId),
                        "grantedRole", request.role().name()), ip);
        return UserSummaryResponse.from(user);
    }

    /**
     * Takes an account's role in one organisation away. When it was the last,
     * the account stops being org-tier and its outstanding tokens die with the
     * role change.
     */
    @Transactional
    public UserSummaryResponse revokeOrgRole(AuthenticatedUser actor, UUID userId, UUID orgId,
            String ip) {
        Long targetOrgId = requireGrantableOrg(actor, orgId);
        User user = requireGrantableUser(actor, userId);
        UserRole previousRole = user.getRole();
        userOrgRoleService.revoke(user, targetOrgId);
        bumpIfRoleChanged(user, previousRole);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.USER_ROLE_UPDATE,
                "user", user.getPublicId(),
                Map.of("previousRole", previousRole.name(), "role", user.getRole().name(),
                        "revokedOrgId", String.valueOf(orgId)), ip);
        return UserSummaryResponse.from(user);
    }

    /** The org the grant names, if this actor may hand out roles in it. */
    private Long requireGrantableOrg(AuthenticatedUser actor, UUID orgId) {
        Long resolved = orgRepository.findByPublicId(orgId).map(Org::getId).orElse(null);
        if (resolved == null) {
            throw orgNotFound();
        }
        if (actor.role().isOrgTier() && !actor.administers(resolved)) {
            throw orgNotFound();
        }
        return resolved;
    }

    /**
     * The account being edited. Two refusals: an actor may not edit itself, and
     * the org tier may not touch a sys-tier account — otherwise an org admin
     * could strip or re-grade the people above it.
     */
    private User requireGrantableUser(AuthenticatedUser actor, UUID userId) {
        User user = userRepository.findByPublicId(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                        "사용자를 찾을 수 없습니다", "해당 ID의 사용자가 존재하지 않습니다."));
        if (actor.id().equals(user.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "자신의 기관 역할은 변경할 수 없습니다.");
        }
        if (user.getRole().isSysTier()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "시스템 관리자 계정의 기관 역할은 변경할 수 없습니다.");
        }
        return user;
    }

    /** A changed effective role invalidates the account's outstanding tokens. */
    private void bumpIfRoleChanged(User user, UserRole previousRole) {
        if (user.getRole() != previousRole) {
            user.bumpTokenVersion();
        }
    }

    private static ApiException orgNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 기관을 찾을 수 없습니다.");
    }

    /**
     * The organisations the account administers after the edit, named publicly
     * and comma-joined. Map.of refuses a null value, so an account under no
     * organisation keeps the literal {@code "null"} this field has always
     * carried there.
     */
    private String orgPublicIdsOrNone(List<Long> orgIds) {
        if (orgIds.isEmpty()) {
            return "null";
        }
        return orgIds.stream()
                .map(id -> orgRepository.findById(id).map(org -> org.getPublicId().toString())
                        .orElse("null"))
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String normalize(String description) {
        return Texts.blankToNull(description);
    }
}
