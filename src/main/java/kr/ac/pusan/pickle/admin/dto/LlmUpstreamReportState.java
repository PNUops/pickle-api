package kr.ac.pusan.pickle.admin.dto;

/** Relationship between the registry and the latest versioned gateway report. */
public enum LlmUpstreamReportState {
    OK,
    NOT_REPORTED,
    MISSING,
    STALE,
    DECONFIGURED,
    UNREGISTERED
}
