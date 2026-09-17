package kr.ac.pusan.pickle.request;

import kr.ac.pusan.pickle.access.ResourceAccessGrant;
import kr.ac.pusan.pickle.access.ResourceAccessGrantRepository;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.admin.dto.ApproveRequestRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Recording that a request was approved, and making what it granted.
 *
 * <p>Two callers reach this: a reviewer pressing approve, and the submission
 * path for a kind whose policy issues without one. They differ in what they
 * check beforehand and in what they tell people afterwards, and in nothing
 * else — so the part in between lives here rather than twice. Two copies of
 * "approved" is how a resource ends up existing with no grant on it, or a
 * review row with no resource behind it, on whichever path was not the one
 * somebody remembered to change.</p>
 */
@Service
public class RequestApproval {

    private final RequestReviewRepository reviewRepository;
    private final ResourceAccessGrantRepository grantRepository;

    public RequestApproval(RequestReviewRepository reviewRepository,
            ResourceAccessGrantRepository grantRepository) {
        this.reviewRepository = reviewRepository;
        this.grantRepository = grantRepository;
    }

    /**
     * Writes the decision, creates the resource and gives it to the requester.
     *
     * <p>Runs inside the caller's transaction. The caller keeps the audit and
     * the notification, because an automatic approval says something different
     * from a reviewed one and neither should be forced to pretend.</p>
     *
     * @param reviewerId the person who decided, or null when the platform did.
     *                   Null is not "we do not know" — it is the record that no
     *                   person was involved, which is why naming the requester
     *                   here would be worse than leaving it empty.
     */
    public RequestTypeHandler.Materialized apply(Request request, RequestTypeHandler handler,
            ApproveRequestRequest form, @Nullable Long reviewerId, AuthenticatedUser actor) {
        reviewRepository.save(RequestReview.approve(request.getId(), reviewerId,
                kr.ac.pusan.pickle.common.text.Texts.blankToNull(form.comment()),
                form.grantedStartDate(), form.grantedEndDate()));
        request.setStatus(RequestStatus.APPROVED);
        RequestTypeHandler.Materialized created = handler.materialize(request, form, actor);

        // The resource starts private: its requester, and nobody else. Anyone
        // who should reach it is added to its access list afterwards, so a
        // resource is never open by default through a step somebody forgot.
        grantRepository.save(ResourceAccessGrant.forUser(request.getResourceType(),
                created.resourceId(), request.getRequesterId(), ResourceRole.OWNER));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                created.afterCommit().run();
            }
        });
        return created;
    }
}
