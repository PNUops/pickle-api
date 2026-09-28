package kr.ac.pusan.pickle.llm.openrouter;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.config.OpenRouterDirectPollingProperties;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Persists a debounced trigger, then wakes the dispatcher only after commit. */
@Service
public class OpenRouterCreditRefreshScheduler {

    private final OpenRouterPollRepository polls;
    private final OpenRouterPollDispatcher dispatcher;
    private final JobScheduler jobs;
    private final Clock clock;
    private final OpenRouterDirectPollingProperties directPolling;

    public OpenRouterCreditRefreshScheduler(OpenRouterPollRepository polls,
            OpenRouterPollDispatcher dispatcher, JobScheduler jobs, Clock clock,
            OpenRouterDirectPollingProperties directPolling) {
        this.polls = polls;
        this.dispatcher = dispatcher;
        this.jobs = jobs;
        this.clock = clock;
        this.directPolling = directPolling;
    }

    public void requestCredits(UUID accountId) {
        afterCommit(() -> requestNow(accountId, false));
    }

    public void requestFull(UUID accountId) {
        afterCommit(() -> requestNow(accountId, true));
    }

    public void requestAfterCredentialChange(UUID accountId) {
        afterCommit(() -> requestCredentialNow(accountId));
    }

    private void requestNow(UUID accountId, boolean full) {
        Instant now = Instant.now(clock);
        if (!polls.requestRefresh(accountId, full, now)) {
            return;
        }
        enqueueDispatcherUnlessDirectPolling();
    }

    private void requestCredentialNow(UUID accountId) {
        if (polls.requestAfterCredentialChange(accountId, Instant.now(clock))) {
            enqueueDispatcherUnlessDirectPolling();
        }
    }

    private void enqueueDispatcherUnlessDirectPolling() {
        if (!directPolling.enabled()) {
            jobs.enqueue(dispatcher::dispatch);
        }
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }
}
