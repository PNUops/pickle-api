package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

@Schema(description = "보존된 TOKEN 이벤트로 읽은 오늘 KST 사용량입니다. 배치 전송 지연이 있고 gateway의 실시간 RPM·TPM·동시성 잔여가 아닙니다. remainingTokens null은 일일 한도 없음이며 이 객체의 null은 관측 읽기 미제공입니다.")
public record LlmTokenObservation(Instant observedAt, LocalDate day, String source,
        long todayTokens, @Nullable Long remainingTokens) { }
