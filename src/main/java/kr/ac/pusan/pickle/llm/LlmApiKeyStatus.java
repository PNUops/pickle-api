package kr.ac.pusan.pickle.llm;

/**
 * What state an issued key is in (DB enum {@code llm_api_key_status}).
 *
 * <p>Anything but {@link #ACTIVE} refuses requests at the gateway; the
 * distinction only changes what the student is told, and that is the whole
 * reason a revoked key keeps its row instead of disappearing — "this key was
 * revoked" and "no such key" are different sentences, and only one of them
 * sends somebody looking for a typo.
 */
public enum LlmApiKeyStatus {
    ACTIVE,
    SUSPENDED,
    REVOKED,
    EXPIRED
}
