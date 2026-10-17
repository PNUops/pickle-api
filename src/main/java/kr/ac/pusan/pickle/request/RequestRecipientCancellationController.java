package kr.ac.pusan.pickle.request;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import kr.ac.pusan.pickle.request.dto.CancelRequestRecipientRequest;
import kr.ac.pusan.pickle.request.dto.RequestRecipientCancellationImpactResponse;
import kr.ac.pusan.pickle.request.dto.RequestRecipientCancellationReceipt;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/requests")

public class RequestRecipientCancellationController {
    private final RequestRecipientCancellationService cancellations;
    public RequestRecipientCancellationController(RequestRecipientCancellationService cancellations) { this.cancellations = cancellations; }

    @GetMapping("/{requestId}/recipients/{recipientId}/cancellation-impact")
    @Operation(summary = "대상자 생성 대기 취소 영향 조회", description = "현재 실제 OWNER으로 승인된 VM 또는 LLM 명단의 한 대상자를 확인합니다. 조회는 상태를 변경하지 않습니다.")
    public RequestRecipientCancellationImpactResponse getRequestRecipientCancellationImpact(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID requestId, @PathVariable UUID recipientId) {
        return cancellations.preview(actor, requestId, recipientId, false);
    }

    @PostMapping("/{requestId}/recipients/{recipientId}/cancel")
    @Operation(summary = "대상자 생성 대기 취소", description = "정확한 현재 영향 확인과 사유로 한 대상자만 취소합니다. 승인, 다른 대상자, 구성원과 초대는 유지하며 등록된 자원은 회수하지 않습니다. 같은 수행자와 확인 지문 및 사유의 재요청은 현재 재인가 후 기존 결과를 반환합니다.")
    public RequestRecipientCancellationReceipt cancelRequestRecipient(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID requestId, @PathVariable UUID recipientId,
            @Valid @RequestBody CancelRequestRecipientRequest request, HttpServletRequest http) {
        return cancellations.cancel(actor, requestId, recipientId, request, false, kr.ac.pusan.pickle.common.web.ClientIps.clientIp(http));
    }
}
