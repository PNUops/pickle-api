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
        @Schema(description = "이 시각까지 연장하지 않으면 레코드가 내려가고 이름이 해제됩니다.")
        Instant renewDueAt,

        @Schema(description = "기한을 바꾼 이유. 감사 기록에 남습니다.")
        String reason) {
}
