package kr.ac.pusan.pickle.llm.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UsageAlertFactsTest {
    @Test
    void remainingJustAboveTwentyPercentDoesNotRoundIntoTheWarning() {
        assertThat(UsageAlertFacts.creditStage(BigDecimal.ONE, new BigDecimal("0.20000000000000001"))).isZero();
        assertThat(UsageAlertFacts.creditStage(BigDecimal.ONE, new BigDecimal("0.2"))).isEqualTo(80);
        assertThat(UsageAlertFacts.creditStage(BigDecimal.ONE, new BigDecimal("0.00000000000000001"))).isEqualTo(80);
        assertThat(UsageAlertFacts.creditStage(BigDecimal.ONE, BigDecimal.ZERO)).isEqualTo(100);
        assertThat(UsageAlertFacts.creditStage(BigDecimal.ONE, new BigDecimal("-0.00000000000000001"))).isEqualTo(100);
    }

    @Test
    void integerThresholdsRemainExactAboveTheBrowserSafeIntegerRange() {
        long cap = 9007199254740993L;
        BigInteger limit = BigInteger.valueOf(cap);
        BigInteger below = limit.multiply(BigInteger.valueOf(80)).divide(BigInteger.valueOf(100));
        assertThat(UsageAlertFacts.tokenStage(below, cap)).isZero();
        assertThat(UsageAlertFacts.tokenStage(below.add(BigInteger.ONE), cap)).isEqualTo(80);
        assertThat(UsageAlertFacts.tokenStage(limit, cap)).isEqualTo(100);
    }

    @Test
    void missingOrImpossibleFactsAreDeferredRatherThanAReportedZero() {
        assertThat(UsageAlertFacts.creditStage(null, BigDecimal.ZERO)).isNull();
        assertThat(UsageAlertFacts.creditStage(BigDecimal.ONE, null)).isNull();
        assertThat(UsageAlertFacts.creditStage(BigDecimal.ONE, new BigDecimal("1.01"))).isNull();
        assertThat(UsageAlertFacts.tokenStage(BigInteger.ZERO, null)).isNull();
        assertThat(UsageAlertFacts.tokenStage(BigInteger.ZERO, 0L)).isNull();
        assertThat(UsageAlertFacts.tokenStage(BigInteger.ZERO, 100L)).isZero();
    }

    @Test
    void stableBasisDoesNotIncludePollTimeAndNormalizesTheSameCap() {
        Instant now = Instant.parse("2026-11-02T01:00:00Z"); UUID key = UUID.randomUUID();
        var first = UsageAlertFacts.credit(new BigDecimal("1.00"), new BigDecimal("0.20"), UsageAlertPeriod.DAILY, now, now);
        var next = UsageAlertFacts.credit(BigDecimal.ONE, new BigDecimal("0.1"), UsageAlertPeriod.DAILY, now.plusSeconds(600), now.plusSeconds(600));
        assertThat(first.basis(key)).isEqualTo(next.basis(key));
        assertThat(first.basis(key)).isNotEqualTo(UsageAlertFacts.credit(new BigDecimal("2"), BigDecimal.ONE,
                UsageAlertPeriod.DAILY, now, now).basis(key));
    }

    @Test
    void providerWindowsUseUtcMondayAndMonthStartWhileTokenUsesKst() {
        Instant sunday = Instant.parse("2026-11-01T16:00:00Z");
        assertThat(UsageAlertFacts.periodStart(UsageAlertPeriod.WEEKLY, sunday)).isEqualTo(Instant.parse("2026-10-26T00:00:00Z"));
        assertThat(UsageAlertFacts.periodStart(UsageAlertPeriod.MONTHLY, sunday)).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(UsageAlertFacts.periodEnd(UsageAlertPeriod.MONTHLY, sunday)).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(UsageAlertFacts.token(BigInteger.ONE, 100, sunday, sunday).start()).isEqualTo(Instant.parse("2026-11-01T15:00:00Z"));
        assertThat(UsageAlertFacts.periodStart(UsageAlertPeriod.TOTAL, sunday)).isNull();
    }
}
