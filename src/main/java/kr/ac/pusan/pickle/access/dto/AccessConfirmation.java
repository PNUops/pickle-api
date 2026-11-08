package kr.ac.pusan.pickle.access.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** An explicit acknowledgement of the actor's projected access increase. */
public record AccessConfirmation(
        @Schema(description = "권한을 얻는 목적이며 권한 증가 내용과 함께 감사 로그에 기록해요.")
        @Size(max = 200, message = "목적은 200자 이내로 입력해 주세요.") String purpose,
        @Schema(description = "미리보기에서 받은 대상과 변경, 현재 권한의 정확한 지문") String fingerprint,
        @Schema(description = "미리보기의 권한 증가를 명시적으로 확인했을 때만 true") boolean confirmed) { }
