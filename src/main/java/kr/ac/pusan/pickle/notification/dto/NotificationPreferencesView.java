package kr.ac.pusan.pickle.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record NotificationPreferencesView(
        @Schema(description = "새 키 사용량 임계 알림 메일 수신 여부예요. 저장 전 기본값은 true이며, 콘솔 알림과 기존 업무 및 보안 메일에는 영향을 주지 않아요.")
        boolean usageMailEnabled) { }
