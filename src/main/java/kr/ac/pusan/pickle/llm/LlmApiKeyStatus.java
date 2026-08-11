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
    /**
     * Approved, but the secret has not been minted yet. Only the owner may ever
     * see a plaintext, and the approver is not there when they do, so approval
     * creates the key and the owner issues it.
     *
     * <p>The gateway never hears about this state: a key with no secret cannot
     * authenticate anything, so it is absent from the document rather than
     * present and refused.
     */
    PENDING,
    ACTIVE,
    SUSPENDED,
    REVOKED,
    EXPIRED
}
