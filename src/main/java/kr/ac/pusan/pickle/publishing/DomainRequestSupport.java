package kr.ac.pusan.pickle.publishing;

import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.ApproveRequestRequest;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.publishing.dto.CreateDomainRequestSpec;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.request.RequestTypeHandler;
import kr.ac.pusan.pickle.request.dto.CreateRequestRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A name asked for through the request flow.
 *
 * <p>Whether a request of this kind waits for a person is the root's answer,
 * not this class's — {@link DomainIssuancePolicy} holds it and the common
 * request flow reads it. What is here is the same for both routes: what the
 * form has to contain, and what approving it creates.</p>
 *
 * <p><b>Everything is checked twice, and the second time is the one that
 * counts.</b> A request under a reviewed root can sit in a queue for days, and
 * in that time the root can lose its row, its organisation can be disabled, the
 * reserved-word list can grow, the workspace can fill its cap and somebody else
 * can take the name. Validating at submission buys a decent error message while
 * the applicant is still looking at the form; only {@link DnsDomainService#issue}
 * runs under the locks that make the answer true.</p>
 */
@Component
public class DomainRequestSupport implements RequestTypeHandler {

    private final DnsDomainService domainService;
    private final SubdomainPolicy subdomainPolicy;
    private final DomainIssuancePolicy issuancePolicy;
    private final DomainRepository domainRepository;
    private final kr.ac.pusan.pickle.workspace.WorkspaceRepository workspaceRepository;
    private final JdbcTemplate jdbc;

    public DomainRequestSupport(DnsDomainService domainService, SubdomainPolicy subdomainPolicy,
            DomainIssuancePolicy issuancePolicy, DomainRepository domainRepository,
            kr.ac.pusan.pickle.workspace.WorkspaceRepository workspaceRepository,
            JdbcTemplate jdbc) {
        this.domainService = domainService;
        this.subdomainPolicy = subdomainPolicy;
        this.issuancePolicy = issuancePolicy;
        this.domainRepository = domainRepository;
        this.workspaceRepository = workspaceRepository;
        this.jdbc = jdbc;
    }

    @Override
    public ResourceType type() {
        return ResourceType.DOMAIN;
    }

    @Override
    public boolean ownsItsOwnLifetime() {
        return true;
    }

    @Override
    public void validateCreate(CreateRequestRequest form, List<FieldValidationError> errors) {
        CreateDomainRequestSpec spec = form.domain();
        if (spec == null || spec.label() == null || spec.rootDomain() == null) {
            errors.add(new FieldValidationError("domain", "발급받을 이름과 루트 도메인을 골라 주세요."));
            return;
        }
        String label = spec.label().strip().toLowerCase(java.util.Locale.ROOT);
        String root = spec.rootDomain().strip().toLowerCase(java.util.Locale.ROOT);
        int before = errors.size();
        subdomainPolicy.validateLabel(label, "domain.label", errors);
        subdomainPolicy.validateRootDomain(root, "domain.rootDomain", errors);
        if (errors.size() > before) {
            return;
        }
        // A root that is allowed by the setting but has no row of its own is
        // still not issuable, and it is the row that carries the organisation.
        try {
            issuancePolicy.requireIssuable(root);
        } catch (RuntimeException refused) {
            errors.add(new FieldValidationError("domain.rootDomain",
                    "이 루트 도메인으로는 지금 이름을 발급할 수 없습니다."));
            return;
        }
        String fqdn = label + "." + root;
        Domain held = domainRepository.findFirstByFqdnAndStatusNot(fqdn, DomainStatus.REMOVED)
                .orElse(null);
        if (held != null) {
            // Their own reserved name gets sent to the door that recovers it.
            // Without this split the owner who released a name by mistake comes
            // to the request form to get it back and is told to pick a different
            // one — and the message that knows better sits on a path only a
            // reviewer can reach.
            boolean theirs = held.getReleasedAt() != null
                    && workspaceOf(form).map(id -> id.equals(held.getWorkspaceId())).orElse(false);
            errors.add(new FieldValidationError("domain.label", theirs
                    ? "이 워크스페이스가 예약 중인 이름입니다. 도메인 목록에서 되살려 주세요."
                    : "이미 사용 중인 이름입니다."));
            return;
        }
        // The cap, at submission as well as at creation. It is counted under a
        // lock when the name is made, so this cannot be the only check — but
        // without it a workspace at its limit submits, waits, and the conflict
        // surfaces in front of a reviewer who did not cause it and cannot fix
        // it. Pending requests count too, or five queued requests all pass and
        // the last four fail one at a time.
        workspaceOf(form).ifPresent(workspaceId -> {
            long standing = domainRepository.countByWorkspaceIdAndKindAndStatusNot(workspaceId,
                    DomainKind.EXTERNAL, DomainStatus.REMOVED);
            Integer queued = jdbc.queryForObject("""
                    select count(*) from domain_request_details d
                      join requests r on r.id = d.request_id
                     where r.status = 'SUBMITTED' and r.workspace_id = ?
                    """, Integer.class, workspaceId);
            if (standing + (queued == null ? 0 : queued)
                    >= DnsDomainService.DEFAULT_DOMAINS_PER_WORKSPACE) {
                errors.add(new FieldValidationError("domain.label",
                        "이 워크스페이스가 가질 수 있는 도메인 수를 다 썼습니다. 검토를 기다리는 신청도 "
                                + "함께 셉니다. 해제한 이름도 예약 기간 동안은 자리를 차지합니다."));
            }
        });
        // Two requests for one name is the collision this catches. Nothing
        // reserves a name at submission, so under a reviewed root both would
        // pass and the second approval would fail on the unique index — in
        // front of a reviewer, for a mistake the reviewer did not make. The
        // hostname axis of a VM request already refuses on the same grounds.
        Integer pending = jdbc.queryForObject("""
                select count(*) from domain_request_details d
                  join requests r on r.id = d.request_id
                 where r.status = 'SUBMITTED' and d.label = ? and d.root_domain = ?
                """, Integer.class, label, root);
        if (pending != null && pending > 0) {
            errors.add(new FieldValidationError("domain.label",
                    "다른 신청이 검토를 기다리고 있는 이름입니다."));
        }
    }

    @Override
    public boolean isAutoApproved(CreateRequestRequest form) {
        return issuancePolicy.isAutoApproved(root(form));
    }

    @Override
    public java.util.Optional<Long> owningOrgId(CreateRequestRequest form) {
        // The root decides, and the form's own orgId is not consulted. Two
        // answers about whose a name is would be one too many, and the root is
        // the one the administrator's listing is scoped on.
        return java.util.Optional.of(issuancePolicy.requireIssuable(root(form)).getOrgId());
    }

    /** The internal id of the workspace this form names, when it names a real one. */
    private java.util.Optional<Long> workspaceOf(CreateRequestRequest form) {
        return form.workspaceId() == null ? java.util.Optional.empty()
                : workspaceRepository.findByPublicIdAndDeletedAtIsNull(form.workspaceId())
                        .map(kr.ac.pusan.pickle.workspace.Workspace::getId);
    }

    private static String root(CreateRequestRequest form) {
        return form.domain() == null || form.domain().rootDomain() == null
                ? "" : form.domain().rootDomain().strip().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public void saveDetail(Request request, CreateRequestRequest form) {
        jdbc.update("insert into domain_request_details(request_id, label, root_domain) values (?, ?, ?)",
                request.getId(), form.domain().label().strip().toLowerCase(),
                form.domain().rootDomain().strip().toLowerCase());
    }

    @Override
    public Map<String, Object> submitAuditArgs(Request request) {
        return jdbc.queryForObject(
                "select label, root_domain from domain_request_details where request_id = ?",
                (rs, row) -> Map.<String, Object>of("fqdn",
                        rs.getString("label") + "." + rs.getString("root_domain")),
                request.getId());
    }

    @Override
    public void validateApprove(Request request, ApproveRequestRequest form,
            List<FieldValidationError> errors) {
        // Nothing to decide. A reviewer approves this name or refuses it; there
        // are no dials to set, which is why ApproveRequest grew no member for
        // this kind.
    }

    @Override
    public Materialized materialize(Request request, ApproveRequestRequest form,
            AuthenticatedUser actor) {
        Map<String, String> ask = jdbc.queryForObject(
                "select label, root_domain from domain_request_details where request_id = ?",
                (rs, row) -> Map.of("label", rs.getString("label"),
                        "root", rs.getString("root_domain")),
                request.getId());
        Domain domain = domainService.issue(request.getWorkspaceId(), ask.get("label"),
                ask.get("root"));
        jdbc.update("update domain_request_details set granted_fqdn = ? where request_id = ?",
                domain.getFqdn(), request.getId());
        // The public id rides along so the approval notice can point at the
        // name's own screen. Without it the mail sends the reader to a finished
        // request, and the thing they have to do next — add the records — is
        // one more hop away with nothing saying where.
        return new Materialized(domain.getId(), domain.getFqdn(),
                Map.of("fqdn", domain.getFqdn()), () -> { },
                Map.of("domainId", domain.getPublicId()));
    }
}
