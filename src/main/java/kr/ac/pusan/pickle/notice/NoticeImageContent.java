package kr.ac.pusan.pickle.notice;

/**
 * The bytes of one stored image and the type they were stored under — what the
 * serving path hands to the response. The type is the one determined by reading
 * the bytes at upload, never the one a client declared.
 */
public record NoticeImageContent(String contentType, byte[] bytes) {
}
