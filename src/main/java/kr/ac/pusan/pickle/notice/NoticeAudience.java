package kr.ac.pusan.pickle.notice;

/**
 * Contract {@code NoticeAudience}: how far a notice reaches. PUBLIC is readable
 * without a session (the landing page); USERS demands one. Only a
 * {@link NoticeScope#PLATFORM} notice may be PUBLIC.
 */
public enum NoticeAudience {
    PUBLIC, USERS
}
