package kr.ac.pusan.pickle.notice;

/**
 * Contract {@code NoticeScope}: whose notice this is. ORG additionally forces
 * {@link NoticeAudience#USERS} — an organisation's notice is never readable
 * anonymously (the {@code notices_public_is_platform_check} constraint).
 */
public enum NoticeScope {
    PLATFORM, ORG
}
