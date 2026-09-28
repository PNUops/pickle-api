package kr.ac.pusan.pickle.llm.openrouter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/** Adds the candidate direct-poll configuration only after explicit opt-in. */
@Configuration
@ConditionalOnProperty(prefix = "pickle.openrouter.direct-polling",
        name = "enabled", havingValue = "true")
public class OpenRouterDirectPollingConfiguration {

    public OpenRouterDirectPollingConfiguration(
            @Value("${jobrunr.background-job-server.enabled:true}") boolean jobRunrWorkerEnabled) {
        OpenRouterDirectPollingWorkerGuard.requireWorkerDisabled(jobRunrWorkerEnabled, false);
    }
}
