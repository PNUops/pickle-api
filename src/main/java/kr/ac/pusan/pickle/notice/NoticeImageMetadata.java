package kr.ac.pusan.pickle.notice;

import java.util.UUID;

/**
 * Everything about a notice image except the bytes.
 *
 * <p>A closed Spring Data projection, so the query it backs selects these
 * columns and leaves {@code data} in the database. Every path but the one that
 * serves a single image reads this — a page of notices with five images each
 * would otherwise pull ten megabytes through the connection to render a list of
 * links.</p>
 */
public interface NoticeImageMetadata {

    Long getNoticeId();

    UUID getPublicId();

    String getFileName();

    String getContentType();

    int getByteSize();

    int getSortOrder();
}
