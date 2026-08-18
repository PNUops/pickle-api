package kr.ac.pusan.pickle.sshkey.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.sshkey.VmSshKey;
import org.springframework.lang.Nullable;

/** The non-secret facts about a VM's issued key. */
@Schema(description = "VM에 발급된 SSH 키 정보 (개인키는 포함하지 않는다)")
public record VmSshKeyView(
        @Schema(description = "키 식별자") UUID id,
        @Schema(description = "SHA256 지문 (ssh-keygen -lf 출력과 같은 형식)",
                example = "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG")
        String fingerprint,
        @Schema(description = "내려받을 개인키 파일 이름", example = "pickle-algo-judge.pem")
        String fileName,
        @Schema(description = "발급 시각") Instant createdAt,
        @Schema(description = "이 키로 마지막에 접속한 시각 (없으면 null)")
        @Nullable Instant lastUsedAt) {

    public static VmSshKeyView of(VmSshKey key, String fileName) {
        return new VmSshKeyView(key.getPublicId(), key.getFingerprintSha256(), fileName,
                key.getCreatedAt(), key.getLastUsedAt());
    }
}
