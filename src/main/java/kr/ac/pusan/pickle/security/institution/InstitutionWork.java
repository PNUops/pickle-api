package kr.ac.pusan.pickle.security.institution;

import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Fixed work identifiers describe authority, not response-field projections. */
public enum InstitutionWork {
    VM_REQUEST_READ("vm.request.read", InstitutionWorkGroup.VM, Boundary.RESOURCE, false),
    VM_REQUEST_DECIDE("vm.request.decide", InstitutionWorkGroup.VM, Boundary.RESOURCE, true),
    VM_ROSTER_ISSUE("vm.roster.issue", InstitutionWorkGroup.VM, Boundary.RESOURCE, true),
    VM_RECIPIENT_RETRY("vm.recipient.retry", InstitutionWorkGroup.VM, Boundary.RESOURCE, true),
    VM_METADATA_READ("vm.metadata.read", InstitutionWorkGroup.VM, Boundary.RESOURCE, false),
    VM_OBSERVE("vm.observe", InstitutionWorkGroup.VM, Boundary.RESOURCE, false),
    VM_POWER("vm.power", InstitutionWorkGroup.VM, Boundary.RESOURCE, true),
    VM_PERIOD("vm.period", InstitutionWorkGroup.VM, Boundary.RESOURCE, true),
    VM_DELETION_SCHEDULE("vm.deletion.schedule", InstitutionWorkGroup.VM, Boundary.RESOURCE, true),
    VM_NETWORK_POLICY_READ("vm.network-policy.read", InstitutionWorkGroup.VM, Boundary.RESOURCE, false),
    VM_NETWORK_POLICY_WRITE("vm.network-policy.write", InstitutionWorkGroup.VM, Boundary.RESOURCE, true),
    VM_PUBLICATION_READ("vm.publication.read", InstitutionWorkGroup.VM, Boundary.RESOURCE, false),
    VM_PUBLICATION_OPERATE("vm.publication.operate", InstitutionWorkGroup.VM, Boundary.RESOURCE, true),
    VM_CUSTOM_DOMAIN_VERIFY("vm.custom-domain.verify", InstitutionWorkGroup.VM, Boundary.RESOURCE, true),
    VM_TERMINAL_SESSION_READ("vm.terminal-session.read", InstitutionWorkGroup.VM, Boundary.RESOURCE, false),
    VM_REQUEST_SUPPORT_READ("vm.request.support.read", InstitutionWorkGroup.VM, Boundary.RESOURCE_SUPPORT, false),
    VM_METADATA_SUPPORT_READ("vm.metadata.support.read", InstitutionWorkGroup.VM, Boundary.RESOURCE_SUPPORT, false),
    VM_ACL_SUPPORT_READ("vm.acl.support.read", InstitutionWorkGroup.VM, Boundary.RESOURCE_SUPPORT, false),
    VM_ACL_SUPPORT_MANAGE("vm.acl.support.manage", InstitutionWorkGroup.VM, Boundary.RESOURCE_SUPPORT, true),

    LLM_REQUEST_READ("llm.request.read", InstitutionWorkGroup.LLM, Boundary.RESOURCE, false),
    LLM_REQUEST_DECIDE("llm.request.decide", InstitutionWorkGroup.LLM, Boundary.RESOURCE, true),
    LLM_ROSTER_ISSUE("llm.roster.issue", InstitutionWorkGroup.LLM, Boundary.RESOURCE, true),
    LLM_RECIPIENT_RETRY("llm.recipient.retry", InstitutionWorkGroup.LLM, Boundary.RESOURCE, true),
    LLM_METADATA_READ("llm.metadata.read", InstitutionWorkGroup.LLM, Boundary.RESOURCE, false),
    LLM_OBSERVE("llm.observe", InstitutionWorkGroup.LLM, Boundary.RESOURCE, false),
    LLM_TOKEN_OPERATE("llm.token.operate", InstitutionWorkGroup.LLM, Boundary.RESOURCE, true),
    LLM_STATE_OPERATE("llm.state.operate", InstitutionWorkGroup.LLM, Boundary.RESOURCE, true),
    LLM_PERIOD("llm.period", InstitutionWorkGroup.LLM, Boundary.RESOURCE, true),
    LLM_REVOKE("llm.revoke", InstitutionWorkGroup.LLM, Boundary.RESOURCE, true),
    LLM_KEY_COST_READ("llm.key.cost.read", InstitutionWorkGroup.LLM, Boundary.RESOURCE, false),
    LLM_KEY_BUDGET("llm.key.budget", InstitutionWorkGroup.LLM, Boundary.RESOURCE, true),
    LLM_REQUEST_SUPPORT_READ("llm.request.support.read", InstitutionWorkGroup.LLM, Boundary.RESOURCE_SUPPORT, false),
    LLM_METADATA_SUPPORT_READ("llm.metadata.support.read", InstitutionWorkGroup.LLM, Boundary.RESOURCE_SUPPORT, false),
    LLM_OBSERVE_SUPPORT_READ("llm.observe.support.read", InstitutionWorkGroup.LLM, Boundary.RESOURCE_SUPPORT, false),
    LLM_ACL_SUPPORT_READ("llm.acl.support.read", InstitutionWorkGroup.LLM, Boundary.RESOURCE_SUPPORT, false),
    LLM_ACL_SUPPORT_MANAGE("llm.acl.support.manage", InstitutionWorkGroup.LLM, Boundary.RESOURCE_SUPPORT, true),

    DOMAIN_REQUEST_READ("domain.request.read", InstitutionWorkGroup.DOMAIN, Boundary.RESOURCE, false),
    DOMAIN_REQUEST_DECIDE("domain.request.decide", InstitutionWorkGroup.DOMAIN, Boundary.RESOURCE, true),
    DOMAIN_METADATA_READ("domain.metadata.read", InstitutionWorkGroup.DOMAIN, Boundary.RESOURCE, false),
    DOMAIN_OBSERVE("domain.observe", InstitutionWorkGroup.DOMAIN, Boundary.RESOURCE, false),
    DOMAIN_RENEWAL("domain.renewal", InstitutionWorkGroup.DOMAIN, Boundary.RESOURCE, true),
    DOMAIN_RELEASE("domain.release", InstitutionWorkGroup.DOMAIN, Boundary.RESOURCE, true),
    DOMAIN_REQUEST_SUPPORT_READ("domain.request.support.read", InstitutionWorkGroup.DOMAIN, Boundary.RESOURCE_SUPPORT, false),
    DOMAIN_METADATA_SUPPORT_READ("domain.metadata.support.read", InstitutionWorkGroup.DOMAIN, Boundary.RESOURCE_SUPPORT, false),
    DOMAIN_ACL_SUPPORT_READ("domain.acl.support.read", InstitutionWorkGroup.DOMAIN, Boundary.RESOURCE_SUPPORT, false),
    DOMAIN_ACL_SUPPORT_MANAGE("domain.acl.support.manage", InstitutionWorkGroup.DOMAIN, Boundary.RESOURCE_SUPPORT, true),

    GPU_REQUEST_READ("gpu.request.read", InstitutionWorkGroup.GPU, Boundary.RESOURCE, false),
    GPU_REQUEST_DECIDE("gpu.request.decide", InstitutionWorkGroup.GPU, Boundary.RESOURCE, true),
    GPU_METADATA_READ("gpu.metadata.read", InstitutionWorkGroup.GPU, Boundary.RESOURCE, false),
    GPU_OBSERVE("gpu.observe", InstitutionWorkGroup.GPU, Boundary.RESOURCE, false),
    GPU_PERIOD("gpu.period", InstitutionWorkGroup.GPU, Boundary.RESOURCE, true),
    GPU_RECALL("gpu.recall", InstitutionWorkGroup.GPU, Boundary.RESOURCE, true),
    GPU_REQUEST_SUPPORT_READ("gpu.request.support.read", InstitutionWorkGroup.GPU, Boundary.RESOURCE_SUPPORT, false),
    GPU_METADATA_SUPPORT_READ("gpu.metadata.support.read", InstitutionWorkGroup.GPU, Boundary.RESOURCE_SUPPORT, false),
    GPU_OBSERVE_SUPPORT_READ("gpu.observe.support.read", InstitutionWorkGroup.GPU, Boundary.RESOURCE_SUPPORT, false),
    GPU_ACL_SUPPORT_READ("gpu.acl.support.read", InstitutionWorkGroup.GPU, Boundary.RESOURCE_SUPPORT, false),
    GPU_ACL_SUPPORT_MANAGE("gpu.acl.support.manage", InstitutionWorkGroup.GPU, Boundary.RESOURCE_SUPPORT, true),

    WORKSPACE_SUPPORT_READ("workspace.support.read", InstitutionWorkGroup.WORKSPACE, Boundary.WORKSPACE_SUPPORT, false),
    WORKSPACE_INFO_UPDATE("workspace.info.update", InstitutionWorkGroup.WORKSPACE, Boundary.WORKSPACE_SUPPORT, true),
    WORKSPACE_MEMBERS_MANAGE("workspace.members.manage", InstitutionWorkGroup.WORKSPACE, Boundary.WORKSPACE_SUPPORT, true),
    WORKSPACE_INVITATIONS_MANAGE("workspace.invitations.manage", InstitutionWorkGroup.WORKSPACE, Boundary.WORKSPACE_SUPPORT, true),
    WORKSPACE_OWNERS_MANAGE("workspace.owners.manage", InstitutionWorkGroup.WORKSPACE, Boundary.WORKSPACE_SUPPORT, true),
    WORKSPACE_TERMINATE("workspace.terminate", InstitutionWorkGroup.WORKSPACE, Boundary.WORKSPACE_SUPPORT, true),
    WORKSPACE_RECIPIENT_CANCEL("workspace.recipient.cancel", InstitutionWorkGroup.WORKSPACE, Boundary.WORKSPACE_SUPPORT, true),
    WORKSPACE_ANNOUNCEMENT_SEND("workspace.announcement.send", InstitutionWorkGroup.WORKSPACE, Boundary.QUALIFIED_WORKSPACE, true),

    STUDENT_LOOKUP("student.lookup", null, Boundary.GLOBAL, false),
    STUDENT_SUPPORT_READ("student.support.read", null, Boundary.GLOBAL, false),
    STUDENT_PROFILE_CORRECT("student.profile.correct", null, Boundary.GLOBAL, true),
    NOTICE_READ("notice.read", null, Boundary.GLOBAL, false),
    NOTICE_MANAGE("notice.manage", null, Boundary.GLOBAL, true),
    SHARED_CERTIFICATE_READ("shared-certificate.read", null, Boundary.GLOBAL, false),
    ROOT_DOMAIN_CATALOGUE_READ("root-domain.catalogue.read", null, Boundary.GLOBAL, false),
    LLM_MODEL_CATALOGUE_READ("llm.model-catalogue.read", null, Boundary.GLOBAL, false),
    INSTITUTION_AUDIT_READ("institution.audit.read", null, Boundary.INSTITUTION, false),
    INSTITUTION_STAFF_READ("institution.staff.read", null, Boundary.INSTITUTION, false),
    INSTITUTION_REQUEST_MAIL_READ("institution.request-mail.read", null, Boundary.INSTITUTION, false),
    INSTITUTION_REQUEST_MAIL_CONFIGURE("institution.request-mail.configure", null, Boundary.INSTITUTION, true),
    INSTITUTION_ANNOUNCEMENT_READ("institution.announcement.read", null, Boundary.INSTITUTION, false),
    INSTITUTION_ANNOUNCEMENT_SEND("institution.announcement.send", null, Boundary.INSTITUTION, true),
    INSTITUTION_ACCOUNT_READ("institution.account.read", null, Boundary.INSTITUTION, false),
    INSTITUTION_ACCOUNT_FINANCE_READ("institution.account.finance.read", null, Boundary.INSTITUTION, false),
    INSTITUTION_ACCOUNT_MANAGE("institution.account.manage", null, Boundary.INSTITUTION, true),
    INSTITUTION_ACCOUNT_CREDENTIAL_MANAGE("institution.account.credential.manage", null, Boundary.INSTITUTION, true),
    INSTITUTION_ROOT_POLICY_READ("institution.root-policy.read", null, Boundary.INSTITUTION, false),
    INSTITUTION_ROOT_POLICY_WRITE("institution.root-policy.write", null, Boundary.INSTITUTION, true),
    INSTITUTION_DELEGATE("institution.delegate", null, Boundary.INSTITUTION, true);

    public enum Boundary {
        RESOURCE, RESOURCE_SUPPORT, WORKSPACE_SUPPORT, QUALIFIED_WORKSPACE, INSTITUTION, GLOBAL
    }

    private final String id;
    private final @Nullable InstitutionWorkGroup group;
    private final Boundary boundary;
    private final boolean write;

    InstitutionWork(String id, @Nullable InstitutionWorkGroup group, Boundary boundary, boolean write) {
        this.id = id;
        this.group = group;
        this.boundary = boundary;
        this.write = write;
    }

    public String id() { return id; }
    public @Nullable InstitutionWorkGroup group() { return group; }
    public Boundary boundary() { return boundary; }
    public boolean write() { return write; }
    public boolean selectable() { return this != INSTITUTION_DELEGATE; }

    public static Optional<InstitutionWork> fromId(String id) {
        return Arrays.stream(values()).filter(work -> work.id.equals(id)).findFirst();
    }
}
