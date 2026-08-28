package kr.ac.pusan.pickle.notice;

/**
 * Contract {@code NoticeAudience}: how far a notice reaches. PUBLIC is readable
 * without a session (the landing page); USERS demands one.
 *
 * <p>Since V95 this is the only axis a notice has, so together with the
 * publication window it is the whole of what separates an anonymous reader from
 * a signed-in one. Any administrator may choose either value.</p>
 */
public enum NoticeAudience {
    PUBLIC, USERS
}
