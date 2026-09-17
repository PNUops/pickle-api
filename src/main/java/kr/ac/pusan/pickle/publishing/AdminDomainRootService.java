package kr.ac.pusan.pickle.publishing;

import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.publishing.dto.AdminDomainRootView;
import kr.ac.pusan.pickle.publishing.dto.UpdateDomainRootRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The roots an administrator can see, and the one thing they can change there.
 *
 * <p>Which roots exist is not editable here. A root is a DNS zone somebody
 * created and delegated, and a row that claimed one existed when it did not
 * would issue names into nowhere — so rows arrive with the deployment that
 * created the zone. What an administrator decides is the policy: whether names
 * under a root they are responsible for are issued on request or reviewed.</p>
 */
@Service
public class AdminDomainRootService {

    private final DomainRootRepository rootRepository;
    private final DomainRepository domainRepository;
    private final OrgRepository orgRepository;
    private final AuditService auditService;

    public AdminDomainRootService(DomainRootRepository rootRepository,
            DomainRepository domainRepository, OrgRepository orgRepository,
            AuditService auditService) {
        this.rootRepository = rootRepository;
        this.domainRepository = domainRepository;
        this.orgRepository = orgRepository;
        this.auditService = auditService;
    }

    private static ApiException rootNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "루트 도메인을 찾을 수 없습니다", "등록된 루트 도메인이 아닙니다.");
    }

    /**
     * Every root, with the count of names standing under it.
     *
     * <p>Unscoped on purpose, unlike the domain listing beside it. A root is
     * not a resource inside an organisation — it is a name space shared with
     * every other one, and an administrator deciding their own root's policy
     * needs to see that somebody else's root exists before they pick a name
     * that collides with it. The count is what makes the policy legible:
     * turning review on for a root with two hundred names under it is a
     * different act from turning it on for an empty one.</p>
     */
    @Transactional(readOnly = true)
    public List<AdminDomainRootView> list(AuthenticatedUser actor) {
        return rootRepository.findAll().stream()
                .map(root -> new AdminDomainRootView(root.getRootDomain(),
                        orgRepository.findById(root.getOrgId()).map(Org::getPublicId).orElse(null),
                        orgRepository.findById(root.getOrgId()).map(Org::getName).orElse(""),
                        root.isAutoApprove(),
                        domainRepository.countByRootDomainAndKindAndStatusNot(root.getRootDomain(),
                                DomainKind.EXTERNAL, DomainStatus.REMOVED)))
                .toList();
    }

    /**
     * Turns review on or off for one root.
     *
     * <p>Applies to what arrives next, and to nothing already in the ground.
     * Names issued under the old policy were issued legitimately, and reaching
     * back to un-approve them would take away something a rule granted at the
     * time it was granted. Requests already waiting keep waiting — turning
     * review off does not sweep the queue, because a reviewer may already have
     * reasons to refuse one of them.</p>
     */
    @Transactional
    public AdminDomainRootView update(AuthenticatedUser actor, String rootDomain,
            UpdateDomainRootRequest form, String ip) {
        DomainRoot root = rootRepository.findByRootDomain(rootDomain)
                .orElseThrow(AdminDomainRootService::rootNotFound);
        // Reading every root is deliberate; writing one is not. An organisation
        // administrator decides for the name spaces their own institution owns,
        // and the refusal is the same not-found a root they cannot see would
        // give — telling them it exists but is somebody else's is the sentence
        // the read surface already declines to write.
        if (actor.role().isOrgTier() && !actor.operates(root.getOrgId())) {
            throw rootNotFound();
        }
        boolean before = root.isAutoApprove();
        root.setAutoApprove(form.autoApprove());
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.DOMAIN_ROOT_POLICY, "domain_root", null,
                Map.of("rootDomain", rootDomain, "autoApprove", form.autoApprove(),
                        "previous", before),
                ip);
        return new AdminDomainRootView(root.getRootDomain(),
                orgRepository.findById(root.getOrgId()).map(Org::getPublicId).orElse(null),
                orgRepository.findById(root.getOrgId()).map(Org::getName).orElse(""),
                root.isAutoApprove(),
                domainRepository.countByRootDomainAndKindAndStatusNot(rootDomain,
                        DomainKind.EXTERNAL, DomainStatus.REMOVED));
    }
}
