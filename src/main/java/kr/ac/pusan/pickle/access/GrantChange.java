package kr.ac.pusan.pickle.access;

/** The three edits an access list accepts, as the audit trail distinguishes them. */
public enum GrantChange {
    ADD,
    UPDATE,
    REMOVE
}
