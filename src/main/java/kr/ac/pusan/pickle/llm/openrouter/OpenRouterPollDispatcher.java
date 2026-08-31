package kr.ac.pusan.pickle.llm.openrouter;

import java.time.Clock;
import java.time.Instant;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.stereotype.Component;

/** One-minute durable dispatcher; vendor calls remain on account 10/30-minute due slots. */
@Component
public class OpenRouterPollDispatcher {

    static final String JOB_ID = "llm-openrouter-account-poll-dispatcher";

    private final OpenRouterPollRepository polls;
    private final OpenRouterAccountPollJob pollJob;
    private final JobScheduler jobScheduler;
    private final Clock clock;

    public OpenRouterPollDispatcher(OpenRouterPollRepository polls,
            OpenRouterAccountPollJob pollJob, JobScheduler jobScheduler, Clock clock) {
        this.polls = polls;
        this.pollJob = pollJob;
        this.jobScheduler = jobScheduler;
        this.clock = clock;
    }

    @Recurring(id = JOB_ID, interval = "PT1M")
    @Job(name = JOB_ID, retries = 0)
    public void dispatch() {
        Instant now = Instant.now(clock);
        for (Long accountId : polls.dueAccountIds(now)) {
            OpenRouterPollRepository.Claim claim = polls.claim(accountId, now);
            if (claim != null) {
                jobScheduler.enqueue(() -> pollJob.poll(
                        claim.accountPublicId().toString(), claim.token().toString()));
            }
        }
    }
}
