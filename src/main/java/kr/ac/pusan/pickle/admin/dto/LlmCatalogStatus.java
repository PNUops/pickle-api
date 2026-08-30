package kr.ac.pusan.pickle.admin.dto;

/** Whether the upstream's model list contains the configured public catalogue. */
public enum LlmCatalogStatus {
    MATCH,
    MISMATCH,
    NOT_APPLICABLE,
    UNKNOWN
}
