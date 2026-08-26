package kr.ac.pusan.pickle.notice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Images kept in {@code notice_images.data} (V92).
 *
 * <p>Sound at the sizes this feature allows — a notice carries at most five
 * images of at most 2 MiB — and it ties an image's lifetime to its notice's
 * through the foreign key's {@code on delete cascade}, which no external store
 * would give for free. The read paths stay honest about the cost anyway: only
 * {@link #load} touches the {@code data} column, and the delete is a bulk
 * statement rather than a load-then-remove.</p>
 */
@Component
public class DbNoticeImageStore implements NoticeImageStore {

    private final NoticeImageRepository noticeImageRepository;

    public DbNoticeImageStore(NoticeImageRepository noticeImageRepository) {
        this.noticeImageRepository = noticeImageRepository;
    }

    @Override
    public NoticeImageMeta store(long noticeId, String fileName, String contentType,
            byte[] bytes) {
        Integer highest = noticeImageRepository.maxSortOrder(noticeId);
        NoticeImage saved = noticeImageRepository.save(new NoticeImage(noticeId, fileName,
                contentType, bytes, highest == null ? 0 : highest + 1));
        return metaOf(saved);
    }

    @Override
    public Map<Long, List<NoticeImageMeta>> metadataByNotice(Collection<Long> noticeIds) {
        if (noticeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<NoticeImageMeta>> byNotice = new LinkedHashMap<>();
        List<NoticeImageMetadata> rows = noticeImageRepository
                .findByNoticeIdInOrderByNoticeIdAscSortOrderAscIdAsc(noticeIds);
        for (NoticeImageMetadata row : rows) {
            byNotice.computeIfAbsent(row.getNoticeId(), key -> new ArrayList<>())
                    .add(new NoticeImageMeta(row.getPublicId(), row.getFileName(),
                            row.getContentType(), row.getByteSize(), row.getSortOrder()));
        }
        return byNotice;
    }

    @Override
    public Optional<NoticeImageContent> load(long noticeId, UUID imageId) {
        return noticeImageRepository.findByPublicIdAndNoticeId(imageId, noticeId)
                .map(image -> new NoticeImageContent(image.getContentType(), image.getData()));
    }

    @Override
    public boolean delete(long noticeId, UUID imageId) {
        return noticeImageRepository.deleteOwnedImage(noticeId, imageId) > 0;
    }

    @Override
    public int count(long noticeId) {
        return noticeImageRepository.countByNoticeId(noticeId);
    }

    private static NoticeImageMeta metaOf(NoticeImage image) {
        return new NoticeImageMeta(image.getPublicId(), image.getFileName(),
                image.getContentType(), image.getByteSize(), image.getSortOrder());
    }
}
