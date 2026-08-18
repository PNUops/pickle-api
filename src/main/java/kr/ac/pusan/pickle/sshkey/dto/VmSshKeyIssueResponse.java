package kr.ac.pusan.pickle.sshkey.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The private key itself, returned on issue, re-issue and download.
 *
 * <p>JSON rather than an octet-stream attachment: the console saves it as a file
 * client-side, and keeping the shape ordinary lets the typed client, the
 * problem+json error handling and the reauthentication retry all work unchanged.
 * Every response carrying this is sent with {@code Cache-Control: no-store}.</p>
 */
@Schema(description = "개인키 응답 (Cache-Control: no-store)")
public record VmSshKeyIssueResponse(
        @Schema(description = "OpenSSH 형식 개인키 PEM 전문")
        String privateKey,
        @Schema(description = "저장할 파일 이름", example = "pickle-algo-judge.pem")
        String fileName,
        @Schema(description = "발급된 키의 정보")
        VmSshKeyView key) {
}
