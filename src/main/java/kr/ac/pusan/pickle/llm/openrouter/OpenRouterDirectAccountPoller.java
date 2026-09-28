package kr.ac.pusan.pickle.llm.openrouter;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Executes one claimed poll using only the provider's GET-only capability. */
@Component
public class OpenRouterDirectAccountPoller {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterDirectAccountPoller.class);

    private final OpenRouterPollRepository polls;
    private final OpenRouterCredentialResolver credentials;
    private final OpenRouterReconciler reconciler;
    private final OpenRouterReadClient reader;
    private final Clock clock;

    public OpenRouterDirectAccountPoller(OpenRouterPollRepository polls,
            OpenRouterCredentialResolver credentials, OpenRouterReconciler reconciler,
            OpenRouterReadClient reader, Clock clock) {
        this.polls = polls;
        this.credentials = credentials;
        this.reconciler = reconciler;
        this.reader = reader;
        this.clock = clock;
    }

    public void poll(UUID accountPublicId, UUID claimToken) {
        OpenRouterPollRepository.Claim claim = polls.activeClaim(
                accountPublicId, claimToken, Instant.now(clock));
        if (claim == null) {
            return;
        }
        OpenRouterManagementAccess access;
        try {
            access = credentials.forAccount(accountPublicId).orElse(null);
        } catch (OpenRouterException error) {
            fail(claim, OpenRouterPollRepository.FailureAxis.CREDITS, null, error);
            return;
        }
        if (access == null || access.credentialId() == null
                || access.credentialId() != claim.credentialId()) {
            polls.abandon(claim);
            return;
        }
        if (claim.kind() == OpenRouterPollRepository.PollKind.CREDITS) {
            pollCredits(claim, access);
        } else {
            pollPair(claim, access);
        }
    }

    private void pollCredits(OpenRouterPollRepository.Claim claim,
            OpenRouterManagementAccess access) {
        Instant attemptedAt = Instant.now(clock);
        polls.markCreditsAttempt(claim, attemptedAt);
        try {
            OpenRouterClient.Credits credits = reader.credits(access.secret());
            Instant observedAt = Instant.now(clock);
            if (polls.recordCreditsSuccess(claim, credits, observedAt, observedAt)) {
                credentials.markUsed(access, observedAt);
            } else {
                polls.abandon(claim);
            }
        } catch (OpenRouterException | IllegalStateException error) {
            fail(claim, OpenRouterPollRepository.FailureAxis.CREDITS, access, error);
        }
    }

    private void pollPair(OpenRouterPollRepository.Claim claim,
            OpenRouterManagementAccess access) {
        boolean baselineExists = polls.baselineExists(claim);
        polls.markKeysAttempt(claim, Instant.now(clock));
        OpenRouterReconciler.ScopeObservation keyObservation;
        try {
            keyObservation = reconciler.reconcileAccountReadOnly(access, claim,
                    Instant.now(clock), baselineExists, clock, reader);
        } catch (OpenRouterException | IllegalStateException error) {
            fail(claim, OpenRouterPollRepository.FailureAxis.KEYS, access, error);
            return;
        }
        Instant keysObservedAt = keyObservation.observedAt();
        if (!keyObservation.persisted()) {
            polls.abandon(claim);
            return;
        }
        polls.recordKeysSuccess(claim, keysObservedAt);
        BigDecimal managedUsage = keyObservation.usageComplete()
                ? polls.managedUsageSinceBaseline(claim) : null;
        if (managedUsage == null) {
            fail(claim, OpenRouterPollRepository.FailureAxis.KEYS, access,
                    new OpenRouterException(0, "managed usage observation was incomplete"));
            return;
        }

        Instant attemptedAt = Instant.now(clock);
        polls.markCreditsAttempt(claim, attemptedAt);
        try {
            OpenRouterClient.Credits credits = reader.credits(access.secret());
            Instant creditsObservedAt = Instant.now(clock);
            if (!polls.recordPairSuccess(claim, credits, managedUsage,
                    keyObservation.resetBoundary(), keysObservedAt,
                    creditsObservedAt, creditsObservedAt)) {
                polls.abandon(claim);
            }
        } catch (OpenRouterException | IllegalStateException error) {
            fail(claim, OpenRouterPollRepository.FailureAxis.CREDITS, access, error);
        }
    }

    private void fail(OpenRouterPollRepository.Claim claim,
            OpenRouterPollRepository.FailureAxis axis,
            @Nullable OpenRouterManagementAccess access, RuntimeException error) {
        OpenRouterCredentialError category = OpenRouterErrorClassifier.classify(error);
        Instant now = Instant.now(clock);
        if (access != null) {
            credentials.markVerificationFailure(access, category, now);
        }
        polls.recordFailure(claim, axis, category, now);
        polls.abandon(claim);
        log.warn("OpenRouter direct account poll {} failed on {}: {}",
                claim.accountPublicId(), axis, category);
    }
}
