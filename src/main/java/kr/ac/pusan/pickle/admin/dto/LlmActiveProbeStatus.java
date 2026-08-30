package kr.ac.pusan.pickle.admin.dto;

/** Result vocabulary reported by the gateway's out-of-band models probe. */
public enum LlmActiveProbeStatus {
    OK,
    AUTH_UNVERIFIED,
    FAILED,
    UNKNOWN
}
