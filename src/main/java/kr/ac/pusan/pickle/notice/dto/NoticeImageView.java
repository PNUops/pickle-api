package kr.ac.pusan.pickle.notice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import kr.ac.pusan.pickle.notice.NoticeImageMeta;
import org.jspecify.annotations.Nullable;

/** Contract {@code NoticeImageView}: one image of a notice, without its bytes. */
public record NoticeImageView(
        UUID id,
        @Schema(description = "업로드 당시의 파일 이름. 클라이언트가 보내지 않았으면 비어 있습니다.")
        @Nullable String fileName,
        @Schema(description = "저장된 실제 형식. 업로드 시 파일 선두 바이트로 판별한 값입니다.")
        String contentType,
        int byteSize,
        @Schema(description = "이미지를 내려받는 경로. 인증 없이 열 수 있는지는 공지의 공개 범위를 따릅니다.")
        String url) {

    /**
     * The URL is assembled here and nowhere else. It is a concatenation of
     * public identifiers, which is exactly the operation the type system stops
     * checking, so it has one home and a test asserts the finished string.
     */
    public static NoticeImageView from(UUID noticeId, NoticeImageMeta image) {
        return new NoticeImageView(image.id(), image.fileName(), image.contentType(),
                image.byteSize(), "/api/v1/notices/" + noticeId + "/images/" + image.id());
    }
}
