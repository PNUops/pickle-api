package kr.ac.pusan.pickle.admin.dto;

/** The three state moves a bulk change can make on an LLM key. */
public enum AdminBulkLlmKeyStatusAction {
    SUSPEND,
    RESUME,
    REVOKE
}
