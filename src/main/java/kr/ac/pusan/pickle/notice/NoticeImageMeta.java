package kr.ac.pusan.pickle.notice;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One stored image as the notice paths know it: everything but the bytes.
 *
 * <p>The storage seam's own vocabulary, deliberately not the JPA projection and
 * deliberately not the response DTO — a store that keeps its bytes somewhere
 * other than a database column still describes what it holds in these terms.</p>
 */
public record NoticeImageMeta(
        UUID id,
        @Nullable String fileName,
        String contentType,
        int byteSize,
        int sortOrder) {
}
