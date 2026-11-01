package kr.ac.pusan.pickle.llm.alert;

import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** No bean means no new recurring registration while operational evaluation is disabled. */
@Component
@ConditionalOnProperty(prefix = "pickle.llm.usage-alerts", name = "enabled", havingValue = "true")
public class LlmUsageAlertJob {
    private final LlmUsageAlertService service;
    public LlmUsageAlertJob(LlmUsageAlertService service) { this.service = service; }
    @Recurring(id = "llm-key-usage-alerts", cron = "*/5 * * * *", zoneId = "UTC")
    @Job(name = "LLM key usage threshold evaluation", retries = 0)
    public void evaluate() { service.evaluate(); }
}
