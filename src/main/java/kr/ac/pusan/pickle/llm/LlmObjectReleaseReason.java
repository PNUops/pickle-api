package kr.ac.pusan.pickle.llm;

/**
 * Why the gateway releases a ledger row. The ledger has two more reasons,
 * {@code COMPENSATED} and {@code DUPLICATE}, which only this side writes.
 */
public enum LlmObjectReleaseReason {
    /** A delete through the gateway succeeded at the vendor. */
    DELETED,
    /** The vendor answered 404 or 410 for an object the ledger said was owned. */
    VENDOR_GONE,
    /** The vendor refused the submit, so the intent never got an object. */
    NOT_CREATED
}
