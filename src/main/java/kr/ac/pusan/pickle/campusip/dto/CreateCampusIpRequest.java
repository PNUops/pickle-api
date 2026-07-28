package kr.ac.pusan.pickle.campusip.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Contract op {@code requestVmCampusIp} body. */
public record CreateCampusIpRequest(
        @NotBlank @Size(max = 1000)
        @Schema(description = "신청 목적 (관리자 검토 자료)")
        String purpose,
        @NotEmpty @Size(max = 32)
        @Schema(description = "공개가 필요한 포트 번호 목록 (1~65535, 최대 32개)")
        List<@NotNull Integer> ports) {
}
