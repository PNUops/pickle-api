package kr.ac.pusan.pickle.access.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** An explicit acknowledgement of the actor's projected access increase. */
public record AccessConfirmation(
        @Schema(description = "권한을 얻는 목적. 증가한 권한과 함께 감사에 기록됩니다.")
        @Size(max = 200, message = "목적은 200자 이하여야 합니다.") String purpose,
        @Schema(description = "미리보기에서 받은 정확한 대상·변경·현재 권한의 지문") String fingerprint,
        @Schema(description = "미리보기의 권한 증가를 명시적으로 확인했을 때만 true") boolean confirmed) { }
