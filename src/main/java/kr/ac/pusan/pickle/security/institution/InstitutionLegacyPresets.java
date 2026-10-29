package kr.ac.pusan.pickle.security.institution;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.user.UserRole;

/** Explicit finite upgrade/development fixtures. Request authorization never derives grants from roles. */
public final class InstitutionLegacyPresets {
    private static final Set<InstitutionWork> MANAGER = Set.of(
            InstitutionWork.VM_REQUEST_READ,
            InstitutionWork.VM_REQUEST_DECIDE,
            InstitutionWork.VM_ROSTER_ISSUE,
            InstitutionWork.VM_RECIPIENT_RETRY,
            InstitutionWork.VM_METADATA_READ,
            InstitutionWork.VM_OBSERVE,
            InstitutionWork.VM_POWER,
            InstitutionWork.VM_PERIOD,
            InstitutionWork.VM_NETWORK_POLICY_READ,
            InstitutionWork.VM_NETWORK_POLICY_WRITE,
            InstitutionWork.VM_PUBLICATION_READ,
            InstitutionWork.VM_PUBLICATION_OPERATE,
            InstitutionWork.VM_CUSTOM_DOMAIN_VERIFY,
            InstitutionWork.VM_TERMINAL_SESSION_READ,
            InstitutionWork.VM_REQUEST_SUPPORT_READ,
            InstitutionWork.VM_METADATA_SUPPORT_READ,
            InstitutionWork.VM_ACL_SUPPORT_READ,
            InstitutionWork.VM_ACL_SUPPORT_MANAGE,
            InstitutionWork.LLM_REQUEST_READ,
            InstitutionWork.LLM_REQUEST_DECIDE,
            InstitutionWork.LLM_ROSTER_ISSUE,
            InstitutionWork.LLM_RECIPIENT_RETRY,
            InstitutionWork.LLM_METADATA_READ,
            InstitutionWork.LLM_OBSERVE,
            InstitutionWork.LLM_TOKEN_OPERATE,
            InstitutionWork.LLM_STATE_OPERATE,
            InstitutionWork.LLM_PERIOD,
            InstitutionWork.LLM_KEY_COST_READ,
            InstitutionWork.LLM_KEY_BUDGET,
            InstitutionWork.LLM_REQUEST_SUPPORT_READ,
            InstitutionWork.LLM_METADATA_SUPPORT_READ,
            InstitutionWork.LLM_ACL_SUPPORT_READ,
            InstitutionWork.LLM_ACL_SUPPORT_MANAGE,
            InstitutionWork.DOMAIN_REQUEST_READ,
            InstitutionWork.DOMAIN_REQUEST_DECIDE,
            InstitutionWork.DOMAIN_METADATA_READ,
            InstitutionWork.DOMAIN_OBSERVE,
            InstitutionWork.DOMAIN_RENEWAL,
            InstitutionWork.DOMAIN_RELEASE,
            InstitutionWork.DOMAIN_REQUEST_SUPPORT_READ,
            InstitutionWork.DOMAIN_METADATA_SUPPORT_READ,
            InstitutionWork.DOMAIN_ACL_SUPPORT_READ,
            InstitutionWork.DOMAIN_ACL_SUPPORT_MANAGE,
            InstitutionWork.GPU_REQUEST_READ,
            InstitutionWork.GPU_REQUEST_DECIDE,
            InstitutionWork.GPU_METADATA_READ,
            InstitutionWork.GPU_OBSERVE,
            InstitutionWork.GPU_REQUEST_SUPPORT_READ,
            InstitutionWork.GPU_METADATA_SUPPORT_READ,
            InstitutionWork.GPU_ACL_SUPPORT_READ,
            InstitutionWork.GPU_ACL_SUPPORT_MANAGE,
            InstitutionWork.WORKSPACE_SUPPORT_READ,
            InstitutionWork.WORKSPACE_INFO_UPDATE,
            InstitutionWork.WORKSPACE_MEMBERS_MANAGE,
            InstitutionWork.WORKSPACE_INVITATIONS_MANAGE,
            InstitutionWork.WORKSPACE_OWNERS_MANAGE,
            InstitutionWork.WORKSPACE_TERMINATE,
            InstitutionWork.WORKSPACE_RECIPIENT_CANCEL,
            InstitutionWork.STUDENT_LOOKUP,
            InstitutionWork.STUDENT_SUPPORT_READ,
            InstitutionWork.STUDENT_PROFILE_CORRECT,
            InstitutionWork.NOTICE_READ,
            InstitutionWork.SHARED_CERTIFICATE_READ,
            InstitutionWork.ROOT_DOMAIN_CATALOGUE_READ,
            InstitutionWork.INSTITUTION_AUDIT_READ,
            InstitutionWork.INSTITUTION_STAFF_READ,
            InstitutionWork.INSTITUTION_REQUEST_MAIL_READ,
            InstitutionWork.INSTITUTION_ANNOUNCEMENT_READ,
            InstitutionWork.INSTITUTION_ACCOUNT_READ,
            InstitutionWork.INSTITUTION_ACCOUNT_FINANCE_READ,
            InstitutionWork.INSTITUTION_ACCOUNT_MANAGE,
            InstitutionWork.INSTITUTION_ACCOUNT_CREDENTIAL_MANAGE,
            InstitutionWork.INSTITUTION_ROOT_POLICY_READ,
            InstitutionWork.INSTITUTION_ROOT_POLICY_WRITE,
            InstitutionWork.LLM_MODEL_CATALOGUE_READ,
            InstitutionWork.LLM_OBSERVE_SUPPORT_READ,
            InstitutionWork.GPU_OBSERVE_SUPPORT_READ);
    private static final Set<InstitutionWork> VIEWER = Set.of(
            InstitutionWork.VM_REQUEST_READ,
            InstitutionWork.VM_METADATA_READ,
            InstitutionWork.VM_OBSERVE,
            InstitutionWork.VM_NETWORK_POLICY_READ,
            InstitutionWork.VM_PUBLICATION_READ,
            InstitutionWork.VM_TERMINAL_SESSION_READ,
            InstitutionWork.VM_REQUEST_SUPPORT_READ,
            InstitutionWork.VM_METADATA_SUPPORT_READ,
            InstitutionWork.VM_ACL_SUPPORT_READ,
            InstitutionWork.LLM_REQUEST_READ,
            InstitutionWork.LLM_METADATA_READ,
            InstitutionWork.LLM_OBSERVE,
            InstitutionWork.LLM_KEY_COST_READ,
            InstitutionWork.LLM_REQUEST_SUPPORT_READ,
            InstitutionWork.LLM_METADATA_SUPPORT_READ,
            InstitutionWork.LLM_ACL_SUPPORT_READ,
            InstitutionWork.DOMAIN_REQUEST_READ,
            InstitutionWork.DOMAIN_METADATA_READ,
            InstitutionWork.DOMAIN_OBSERVE,
            InstitutionWork.DOMAIN_REQUEST_SUPPORT_READ,
            InstitutionWork.DOMAIN_METADATA_SUPPORT_READ,
            InstitutionWork.DOMAIN_ACL_SUPPORT_READ,
            InstitutionWork.GPU_REQUEST_READ,
            InstitutionWork.GPU_METADATA_READ,
            InstitutionWork.GPU_OBSERVE,
            InstitutionWork.GPU_REQUEST_SUPPORT_READ,
            InstitutionWork.GPU_METADATA_SUPPORT_READ,
            InstitutionWork.GPU_ACL_SUPPORT_READ,
            InstitutionWork.WORKSPACE_SUPPORT_READ,
            InstitutionWork.STUDENT_LOOKUP,
            InstitutionWork.STUDENT_SUPPORT_READ,
            InstitutionWork.NOTICE_READ,
            InstitutionWork.SHARED_CERTIFICATE_READ,
            InstitutionWork.ROOT_DOMAIN_CATALOGUE_READ,
            InstitutionWork.INSTITUTION_STAFF_READ,
            InstitutionWork.INSTITUTION_REQUEST_MAIL_READ,
            InstitutionWork.INSTITUTION_ANNOUNCEMENT_READ,
            InstitutionWork.INSTITUTION_ACCOUNT_READ,
            InstitutionWork.INSTITUTION_ACCOUNT_FINANCE_READ,
            InstitutionWork.INSTITUTION_ROOT_POLICY_READ,
            InstitutionWork.LLM_MODEL_CATALOGUE_READ,
            InstitutionWork.LLM_OBSERVE_SUPPORT_READ,
            InstitutionWork.GPU_OBSERVE_SUPPORT_READ);

    private InstitutionLegacyPresets() { }

    public static InstitutionAssignment create(UUID account, UUID institution, UserRole previousRole) {
        return switch (previousRole) {
            case ORG_ADMIN -> InstitutionAssignment.full(account, institution, 0);
            case ORG_MANAGER -> selected(account, institution, MANAGER);
            case ORG_VIEWER -> selected(account, institution, VIEWER);
            default -> throw new IllegalArgumentException("Only existing institution roles have a conversion preset");
        };
    }

    private static InstitutionAssignment selected(UUID account, UUID institution, Set<InstitutionWork> work) {
        return InstitutionAssignment.selected(account, institution, work, Map.of(
                InstitutionWorkGroup.VM, InstitutionWorkspaceScope.all(),
                InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.all(),
                InstitutionWorkGroup.DOMAIN, InstitutionWorkspaceScope.all(),
                InstitutionWorkGroup.GPU, InstitutionWorkspaceScope.all(),
                InstitutionWorkGroup.WORKSPACE, InstitutionWorkspaceScope.all()), 0);
    }
}
