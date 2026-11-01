package kr.ac.pusan.pickle.notification;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.notification.dto.NotificationPreferencesView;
import kr.ac.pusan.pickle.notification.dto.UpdateNotificationPreferencesRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/notification-preferences")
public class NotificationPreferencesController {
    private final NotificationPreferencesService service;

    public NotificationPreferencesController(NotificationPreferencesService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "본인 알림 수신 설정 조회", description = "새 키 사용량 임계 알림 메일의 개인 수신 설정을 조회합니다. 콘솔 알림과 다른 업무·보안 알림에는 영향을 주지 않습니다.")
    public NotificationPreferencesView getMyNotificationPreferences(
            @AuthenticationPrincipal AuthenticatedUser actor) { return service.get(actor.id()); }

    @PutMapping
    @Operation(summary = "본인 알림 수신 설정 변경", description = "새 키 사용량 임계 알림 메일만 변경합니다. 기본값은 수신이며 콘솔 알림과 기존 업무·보안 메일은 유지합니다.")
    public NotificationPreferencesView updateMyNotificationPreferences(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return service.update(actor.id(), request.usageMailEnabled());
    }
}
