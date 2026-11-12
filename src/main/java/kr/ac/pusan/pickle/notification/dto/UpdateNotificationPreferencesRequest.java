package kr.ac.pusan.pickle.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record UpdateNotificationPreferencesRequest(
        @Schema(description = "본인의 새 키 사용량 임계 알림 메일 수신 여부예요. 콘솔 알림과 다른 업무 및 보안 메일에는 영향을 주지 않아요.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "사용량 알림 메일 수신 여부를 선택해 주세요.") Boolean usageMailEnabled) { }
