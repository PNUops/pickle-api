package kr.ac.pusan.pickle.admin.dto;

/** Freshness of the gateway's five-second sync heartbeat. */
public enum LlmGatewayReportState {
    NOT_REPORTED,
    FRESH,
    STALE
}
