package kr.ac.pusan.pickle.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.CreateOrgRequest;
import kr.ac.pusan.pickle.admin.dto.OrgDetailResponse;
import kr.ac.pusan.pickle.admin.dto.UpdateOrgRequest;
import kr.ac.pusan.pickle.admin.dto.UpdateUserAdminRequest;
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
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SYS_ADMIN-only org and user administration (contract tag {@code admin};
 * the role gate is enforced by {@code @PreAuthorize} on the controllers).
 */
@Service
public class AdminService {

    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public AdminService(OrgRepository orgRepository, UserRepository userRepository,
            AuditService auditService) {
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
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
        UserRole targetRole = request.role() != null ? request.role() : user.getRole();
        if (targetRole.isOrgTier()) {
            // ORG_ADMIN and ORG_MANAGER both manage one org (per the permission matrix).
            Long orgId = request.orgId() != null
                    ? orgRepository.findByPublicId(request.orgId()).map(Org::getId).orElse(null)
                    : user.getOrgId();
            if (request.orgId() == null && orgId == null) {
                throw ApiException.validationFailed(List.of(new FieldValidationError("orgId",
                        "ORG_ADMIN·ORG_MANAGER 역할에는 관리 기관(orgId)을 지정해야 합니다.")));
            }
            if (orgId == null) {
                throw ApiException.validationFailed(List.of(new FieldValidationError("orgId",
                        "존재하지 않는 기관(orgId)입니다.")));
            }
            user.setOrgId(orgId);
        } else {
            if (request.orgId() != null) {
                throw ApiException.validationFailed(List.of(new FieldValidationError("orgId",
                        "이 역할에는 orgId를 지정할 수 없습니다.")));
            }
            user.setOrgId(null);
        }

        if (request.role() != null && request.role() != previousRole) {
            user.setRole(request.role());
            user.bumpTokenVersion();
        }

        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.USER_ROLE_UPDATE,
                "user", user.getPublicId(),
                Map.of("previousRole", previousRole.name(), "role", user.getRole().name(),
                        "orgId", user.getOrgId() == null ? "null" : String.valueOf(user.getOrgId())), ip);
        return UserSummaryResponse.from(user);
    }

    private static String normalize(String description) {
        return Texts.blankToNull(description);
    }
}
