package kr.ac.pusan.pickle.llm.openrouter;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Performs one claimed account poll; job arguments contain IDs only, never credentials. */
@Component
public class OpenRouterAccountPollJob {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterAccountPollJob.class);

    private final OpenRouterPollRepository polls;
    private final OpenRouterCredentialResolver credentials;
    private final OpenRouterReconciler reconciler;
    private final OpenRouterClient client;
    private final Clock clock;

    public OpenRouterAccountPollJob(OpenRouterPollRepository polls,
            OpenRouterCredentialResolver credentials, OpenRouterReconciler reconciler,
            OpenRouterClient client, Clock clock) {
        this.polls = polls;
        this.credentials = credentials;
        this.reconciler = reconciler;
        this.client = client;
        this.clock = clock;
    }

    @Job(name = "llm-openrouter-account-poll", retries = 0)
    public void poll(String accountIdText, String claimTokenText) {
        UUID accountId = UUID.fromString(accountIdText);
        UUID claimToken = UUID.fromString(claimTokenText);
        OpenRouterPollRepository.Claim claim = polls.activeClaim(
                accountId, claimToken, Instant.now(clock));
        if (claim == null) {
            return;
        }
        OpenRouterManagementAccess access;
        try {
            access = credentials.forAccount(accountId).orElse(null);
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
            OpenRouterClient.Credits credits = client.credits(access.secret());
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
            keyObservation = reconciler.reconcileAccount(
                    access, claim, Instant.now(clock), baselineExists, clock);
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
            OpenRouterClient.Credits credits = client.credits(access.secret());
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
            @org.jspecify.annotations.Nullable OpenRouterManagementAccess access,
            RuntimeException error) {
        OpenRouterCredentialError category = OpenRouterErrorClassifier.classify(error);
        Instant now = Instant.now(clock);
        if (access != null) {
            credentials.markVerificationFailure(access, category, now);
        }
        polls.recordFailure(claim, axis, category, now);
        polls.abandon(claim);
        log.warn("OpenRouter account poll {} failed on {}: {}",
                claim.accountPublicId(), axis, category);
    }
}
