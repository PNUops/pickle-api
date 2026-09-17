package kr.ac.pusan.pickle.publishing;

import java.util.List;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.ApprovalContextContributor;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.DomainContext;
import kr.ac.pusan.pickle.request.Request;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The two facts that decide whether approving this name will work.
 *
 * <p>Neither is a property of the request — both are properties of the world at
 * the moment of reading, and both move while the request waits. A name nobody
 * held at submission can be taken by somebody else, and a workspace one under
 * its cap can fill it. They are the only two ways approval fails at commit,
 * which is the failure a reviewer has no way to anticipate and did not cause.
 * Showing them is cheaper than explaining the conflict afterwards.</p>
 *
 * <p>They are read, not locked. By the time the reviewer presses approve they
 * can already be stale — {@link DnsDomainService#issue} is where the answer is
 * made true, under the locks. This panel shortens the odds; it does not
 * replace the check.</p>
 */
@Component
public class DomainApprovalContextContributor implements ApprovalContextContributor {

    private final JdbcTemplate jdbc;
    private final DomainRepository domainRepository;

    public DomainApprovalContextContributor(JdbcTemplate jdbc, DomainRepository domainRepository) {
        this.jdbc = jdbc;
        this.domainRepository = domainRepository;
    }

    @Override
    public ResourceType type() {
        return ResourceType.DOMAIN;
    }

    @Override
    public Contribution contribute(Request request, List<Long> applicantWorkspaceIds) {
        String fqdn = jdbc.queryForObject(
                "select label || '.' || root_domain from domain_request_details where request_id = ?",
                String.class, request.getId());
        boolean available = fqdn != null && domainRepository
                .findFirstByFqdnAndStatusNot(fqdn, DomainStatus.REMOVED).isEmpty();
        long held = domainRepository.countByWorkspaceIdAndKindAndStatusNot(request.getWorkspaceId(),
                DomainKind.EXTERNAL, DomainStatus.REMOVED);
        return Contribution.domain(new DomainContext(fqdn, available, held,
                DnsDomainService.DEFAULT_DOMAINS_PER_WORKSPACE));
    }
}
