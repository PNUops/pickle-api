package kr.ac.pusan.pickle.admin.dto;

/** Current serving availability derived from active and passive observations. */
public enum LlmUpstreamAvailability {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    UNAVAILABLE
}
