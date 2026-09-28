package kr.ac.pusan.pickle.llm.openrouter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import kr.ac.pusan.pickle.config.OpenRouterDirectPollingProperties;
import org.jobrunr.server.BackgroundJobServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Candidate-only scheduler that claims and executes polls inline without JobRunr. */
@Component
@ConditionalOnProperty(prefix = "pickle.openrouter.direct-polling",
        name = "enabled", havingValue = "true")
public class OpenRouterDirectPollScheduler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterDirectPollScheduler.class);
    private static final Duration STALE_AFTER = Duration.ofMinutes(30);
    private static final Duration STALE_WARNING_INTERVAL = Duration.ofMinutes(30);

    private final OpenRouterPollRepository polls;
    private final OpenRouterDirectAccountPoller poller;
    private final OpenRouterCredentialResolver credentials;
    private final OpenRouterDirectPollingProperties properties;
    private final Clock clock;
    private final Environment environment;
    private final ObjectProvider<BackgroundJobServer> backgroundServer;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "openrouter-direct-poll");
                thread.setDaemon(true);
                return thread;
            });
    private final Map<UUID, Instant> lastStaleWarningAt = new HashMap<>();
    private boolean started;

    public OpenRouterDirectPollScheduler(OpenRouterPollRepository polls,
            OpenRouterDirectAccountPoller poller, OpenRouterCredentialResolver credentials,
            OpenRouterDirectPollingProperties properties, Clock clock,
            Environment environment, ObjectProvider<BackgroundJobServer> backgroundServer) {
        this.polls = polls;
        this.poller = poller;
        this.credentials = credentials;
        this.properties = properties;
        this.clock = clock;
        this.environment = environment;
        this.backgroundServer = backgroundServer;
    }

    @Override
    public synchronized void run(ApplicationArguments args) {
        if (started) {
            throw new IllegalStateException("OpenRouter direct poll scheduler started twice");
        }
        boolean configuredWorkerEnabled = environment.getProperty(
                "jobrunr.background-job-server.enabled", Boolean.class, true);
        OpenRouterDirectPollingWorkerGuard.requireWorkerDisabled(configuredWorkerEnabled,
                backgroundServer.getIfAvailable() != null);
        for (UUID accountPublicId : properties.accountPublicIds()) {
            if (!polls.directPollAccountReady(accountPublicId)) {
                throw allowlistCredentialFailure();
            }
            try {
                OpenRouterManagementAccess access =
                        credentials.forAccount(accountPublicId).orElse(null);
                if (access == null || access.credentialId() == null
                        || !accountPublicId.equals(access.accountPublicId())) {
                    throw allowlistCredentialFailure();
                }
            } catch (OpenRouterException undecryptableCredential) {
                throw allowlistCredentialFailure();
            }
        }
        long delayMillis = properties.fixedDelay().toMillis();
        executor.scheduleWithFixedDelay(this::pollSafely, 0, delayMillis,
                TimeUnit.MILLISECONDS);
        started = true;
        log.info("OpenRouter direct poll scheduler started with the generic JobRunr worker absent");
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }

    private void pollSafely() {
        try {
            pollDueAccounts();
        } catch (RuntimeException error) {
            log.error("OpenRouter direct poll cycle failed: {}", error.getClass().getSimpleName());
        }
    }

    public void pollDueAccounts() {
        for (UUID accountPublicId : properties.accountPublicIds().stream().sorted().toList()) {
            Instant now = Instant.now(clock);
            pollAccountSafely(accountPublicId, now);
            reportStaleAccount(accountPublicId, Instant.now(clock));
        }
    }

    private void pollAccountSafely(UUID accountPublicId, Instant now) {
        try {
            for (Long accountId : polls.dueAccountIds(now, accountPublicId)) {
                OpenRouterPollRepository.Claim claim = polls.claim(accountId, now);
                if (claim == null) {
                    continue;
                }
                if (!properties.accountPublicIds().contains(claim.accountPublicId())) {
                    polls.abandon(claim);
                    continue;
                }
                try {
                    poller.poll(claim.accountPublicId(), claim.token());
                } catch (RuntimeException error) {
                    // Retain the durable lease after an unexpected partial run;
                    // its ten-minute expiry bounds the next retry without hot-looping.
                    log.error("OpenRouter direct poll failed for account {}: {}",
                            accountPublicId, errorCategory(error));
                    return;
                }
            }
        } catch (RuntimeException error) {
            log.error("OpenRouter direct poll failed for account {}: {}",
                    accountPublicId, errorCategory(error));
        }
    }

    private void reportStaleAccount(UUID accountPublicId, Instant now) {
        try {
            OpenRouterPollRepository.DirectPollHealth health =
                    polls.directPollHealth(accountPublicId);
            if (health == null) {
                log.error("OpenRouter direct poll account {} disappeared after startup validation",
                        accountPublicId);
                return;
            }
            boolean creditsStale = isStale(health.creditsLastSuccessAt(), now);
            boolean keysStale = isStale(health.keysLastSuccessAt(), now);
            if (!creditsStale && !keysStale) {
                lastStaleWarningAt.remove(accountPublicId);
                return;
            }
            Instant lastWarning = lastStaleWarningAt.get(accountPublicId);
            if (lastWarning == null || Duration.between(lastWarning, now)
                    .compareTo(STALE_WARNING_INTERVAL) >= 0) {
                log.warn("OpenRouter direct poll is stale for account {}: credits_age_seconds={}, "
                                + "keys_age_seconds={}, credits_error={}, keys_error={}",
                        accountPublicId, ageSeconds(health.creditsLastSuccessAt(), now),
                        ageSeconds(health.keysLastSuccessAt(), now),
                        safeStoredError(health.creditsError()), safeStoredError(health.keysError()));
                lastStaleWarningAt.put(accountPublicId, now);
            }
        } catch (RuntimeException error) {
            log.error("OpenRouter direct poll health read failed for account {}: {}",
                    accountPublicId, errorCategory(error));
        }
    }

    static boolean isStale(Instant lastSuccessAt, Instant now) {
        return lastSuccessAt == null || !lastSuccessAt.plus(STALE_AFTER).isAfter(now);
    }

    private static String ageSeconds(Instant lastSuccessAt, Instant now) {
        return lastSuccessAt == null ? "never"
                : Long.toString(Math.max(0, Duration.between(lastSuccessAt, now).getSeconds()));
    }

    private static String safeStoredError(String error) {
        if (error == null) {
            return "none";
        }
        try {
            return OpenRouterCredentialError.valueOf(error).name();
        } catch (IllegalArgumentException unknown) {
            return "UNKNOWN";
        }
    }

    private static String errorCategory(RuntimeException error) {
        if (error instanceof OpenRouterException) {
            return OpenRouterErrorClassifier.classify(error).name();
        }
        if (error instanceof org.springframework.dao.DataAccessException) {
            return "DATABASE_ERROR";
        }
        return "INTERNAL_ERROR";
    }

    private static IllegalStateException allowlistCredentialFailure() {
        return new IllegalStateException("OpenRouter direct polling allowlist requires ACTIVE "
                + "accounts with decryptable verified ACTIVE credentials");
    }
}
