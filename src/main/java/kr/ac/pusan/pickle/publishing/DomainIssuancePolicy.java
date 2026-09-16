package kr.ac.pusan.pickle.publishing;

import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.OrgStatus;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a root domain says about names taken under it.
 *
 * <p>One place asks this so that two paths cannot end up with two answers. The
 * question has two halves and they fail differently: <em>may a name be issued
 * here at all</em> is a refusal, and <em>does it need a reviewer</em> routes an
 * accepted request. Callers that only need the second still have to pass the
 * first, because a root with no row is not a root with an open policy — it is
 * a root this platform does not issue under.</p>
 *
 * <p><b>Scope, deliberately narrower than the name suggests.</b> Only external
 * issuance consults this today. Publishing a platform subdomain for a VM goes
 * through {@code POST /vms/&#123;vmId&#125;/domains}, which is a direct call with
 * no request behind it and therefore nothing for a reviewer to approve; routing
 * it through a policy that can say "wait" would take away a VM owner's only way
 * to publish and offer nothing in its place. Widening this is a round of its
 * own — the two shapes it could take are in the DNS design document — and until
 * then the administrator's screen says what the value currently covers.</p>
 */
@Service
public class DomainIssuancePolicy {

    private final DomainRootRepository rootRepository;
    private final OrgRepository orgRepository;

    public DomainIssuancePolicy(DomainRootRepository rootRepository, OrgRepository orgRepository) {
        this.rootRepository = rootRepository;
        this.orgRepository = orgRepository;
    }

    /**
     * The root a name may be issued under, refusing when it may not.
     *
     * <p>A root that is allowed by the setting but has no row answers a field
     * error rather than a conflict: the setting says issuing is permitted
     * there, the row says what the root <em>is</em>, and a caller who picked a
     * root with no row picked a value this platform cannot honour. A disabled
     * organisation is the conflict, because the value was fine and the state
     * behind it is not.</p>
     */
    @Transactional(readOnly = true)
    public DomainRoot requireIssuable(String rootDomain) {
        return requireIssuable(rootDomain, true);
    }

    /**
     * The same question asked from a path where no form is being filled in.
     *
     * <p>A field error names a box on the screen the caller is looking at, and
     * from an approval there is no such box: a root that vanished between
     * submission and approval is not something the reviewer typed wrong. That
     * case answers the conflict the disabled-organisation case already answers,
     * so the two refusals an approver can meet have the same shape.</p>
     */
    @Transactional(readOnly = true)
    public DomainRoot requireIssuable(String rootDomain, boolean fromForm) {
        DomainRoot root = rootRepository.findByRootDomain(rootDomain)
                .orElseThrow(() -> fromForm
                        ? ApiException.validationFailed(List.of(
                                new FieldValidationError("rootDomain",
                                        "이 루트 도메인으로는 이름을 발급할 수 없습니다.")))
                        : new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_NOT_ACTIVE,
                                "지금은 이 루트 도메인으로 발급할 수 없습니다",
                                "이 루트 도메인은 더 이상 발급 대상이 아닙니다. 신청자에게 다른 이름을 받아야 합니다."));
        Org org = orgRepository.findById(root.getOrgId()).orElse(null);
        if (org == null || org.getStatus() != OrgStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_NOT_ACTIVE,
                    "지금은 이 루트 도메인으로 발급할 수 없습니다",
                    "이 루트를 소유한 기관이 활성 상태가 아닙니다. 관리자에게 문의해 주세요.");
        }
        return root;
    }

    /** Whether a name asked for under this root is issued without a reviewer. */
    @Transactional(readOnly = true)
    public boolean isAutoApproved(String rootDomain) {
        return rootRepository.findByRootDomain(rootDomain)
                .map(DomainRoot::isAutoApprove)
                .orElse(false);
    }
}
