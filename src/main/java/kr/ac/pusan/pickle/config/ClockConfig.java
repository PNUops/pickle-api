package kr.ac.pusan.pickle.config;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Injectable {@link Clock} so date math (VM expiry, dashboards) is testable
 * with a fixed clock. The bean ticks in UTC; calendar-day semantics are always
 * derived in KST at the use site via {@link #todayKst(Clock)} — the product's
 * contractual timezone (endDate inclusive until midnight KST).
 */
@Configuration
public class ClockConfig {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Today as a KST calendar date. */
    public static LocalDate todayKst(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), KST);
    }
}
