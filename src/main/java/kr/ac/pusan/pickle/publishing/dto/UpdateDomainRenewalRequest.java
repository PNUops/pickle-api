package kr.ac.pusan.pickle.publishing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Contract schema {@code UpdateDomainRenewalRequest}: a new renewal deadline.
 *
 * <p>Both directions are allowed. Pushing a deadline out is the ordinary case —
 * a teaching name that should last the term — and pulling it in is how a name
 * is wound down without taking it away today, which is gentler than a forced
 * release and leaves the owner time to move.</p>
 */
public record UpdateDomainRenewalRequest(
        @NotNull(message = "새 사용 기한을 지정해 주세요.")
        @Schema(description = "이 시각이 지나고 연장하지 않은 이름은 해제 대상이 돼요. 해제할 때 레코드도 삭제해요.")
        Instant renewDueAt,

        @Schema(description = "기한을 바꾼 이유이며, 감사 기록에 남아요.")
        String reason) {
}
