package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

@Schema(description = "보존된 TOKEN 이벤트에서 읽은 오늘의 KST 사용량이에요. 배치 전송 지연이 있으며 Gateway의 실시간 RPM과 TPM, 동시성 잔여를 뜻하지 않아요. remainingTokens가 null이면 일일 한도가 없고, 이 객체가 null이면 관측 읽기를 제공하지 않는다는 뜻이에요.")
public record LlmTokenObservation(Instant observedAt, LocalDate day, String source,
        long todayTokens, @Nullable Long remainingTokens) { }
