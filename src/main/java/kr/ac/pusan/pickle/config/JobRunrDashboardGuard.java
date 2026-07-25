package kr.ac.pusan.pickle.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Refuses to start when the JobRunr dashboard is enabled without basic-auth
 * credentials.
 *
 * <p>JobRunr's own wiring is fail-open here: {@code createOptionalBasicAuthenticator}
 * needs both a username and a password, and silently serves the dashboard with no
 * authentication at all when either is missing. The dashboard exposes job
 * payloads and lets an anonymous caller requeue or delete jobs, so an empty
 * credential is a configuration mistake, not a mode of operation.</p>
 */
@Configuration
public class JobRunrDashboardGuard {

    public JobRunrDashboardGuard(
            @Value("${jobrunr.dashboard.enabled:false}") boolean enabled,
            @Value("${jobrunr.dashboard.username:}") String username,
            @Value("${jobrunr.dashboard.password:}") String password) {
        if (enabled && (isBlank(username) || isBlank(password))) {
            throw new IllegalStateException(
                    "jobrunr.dashboard.enabled is true but the dashboard credentials are not set. "
                            + "Provide PICKLE_JOBRUNR_DASH_USER and PICKLE_JOBRUNR_DASH_PASS via "
                            + "/etc/pickle/api.env, or disable the dashboard.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
