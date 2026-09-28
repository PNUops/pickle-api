package kr.ac.pusan.pickle.llm;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.PreDestroy;
import org.jobrunr.server.BackgroundJobServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Runs the existing usage rollup without consuming the shared JobRunr queue. */
@Component
@ConditionalOnProperty(prefix = "pickle.llm.usage-rollup-direct", name = "enabled", havingValue = "true")
public class LlmDirectUsageRollupScheduler implements ApplicationRunner, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LlmDirectUsageRollupScheduler.class);
    private static final Duration REFRESH_INTERVAL = Duration.ofMinutes(5);
    private static final Duration OBSERVE_INTERVAL = Duration.ofMinutes(1);
    private static final Duration MAX_SUCCESS_AGE = Duration.ofMinutes(15);

    private static final String STATE_SQL = """
            select coalesce((select last_event_id from llm_usage_rollup_state where id), 0),
                   coalesce((select max(id) from llm_usage_events), 0),
                   (select last_success_at from llm_usage_rollup_state where id), now()
            """;

    private final LlmUsageRollupService rollup;
    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;
    private final ObjectProvider<BackgroundJobServer> backgroundServer;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "llm-direct-rollup");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean fenced = new AtomicBoolean();
    private final AtomicReference<Instant> lastObservedFreshAt = new AtomicReference<>();
    private Lag pendingLag;
    private boolean started;

    public LlmDirectUsageRollupScheduler(LlmUsageRollupService rollup,
            JdbcTemplate jdbcTemplate, Environment environment,
            ObjectProvider<BackgroundJobServer> backgroundServer,
            ApplicationEventPublisher events, Clock clock) {
        this.rollup = rollup;
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
        this.backgroundServer = backgroundServer;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public synchronized void run(ApplicationArguments args) {
        if (started) {
            throw new IllegalStateException("LLM direct usage rollup was started twice");
        }
        if (environment.getProperty("jobrunr.background-job-server.enabled", Boolean.class, true)
                || backgroundServer.getIfAvailable() != null) {
            throw new IllegalStateException("LLM direct usage rollup requires JobRunr workers off");
        }
        try {
            RollupState before = observe();
            int rebuilt = rollup.refresh(); // Backfill before application readiness.
            RollupState after = observe();
            if (after.watermark() < before.rawMaxId() || after.stale()) {
                throw new IllegalStateException("LLM usage rollup did not complete its startup backfill");
            }
            lastObservedFreshAt.set(Instant.now(clock));
            log.info("LLM direct usage rollup ready: rebuiltDays={}, watermark={}, rawMaxId={}",
                    rebuilt, after.watermark(), after.rawMaxId());
            executor.scheduleWithFixedDelay(this::refreshSafely, REFRESH_INTERVAL.toSeconds(),
                    REFRESH_INTERVAL.toSeconds(), TimeUnit.SECONDS);
            executor.scheduleWithFixedDelay(this::observeSafely, OBSERVE_INTERVAL.toSeconds(),
                    OBSERVE_INTERVAL.toSeconds(), TimeUnit.SECONDS);
            started = true;
        } catch (RuntimeException failure) {
            executor.shutdownNow();
            throw failure;
        }
    }

    void refreshSafely() {
        if (fenced.get()) {
            return;
        }
        try {
            RollupState before = observe();
            int rebuilt = rollup.refresh();
            RollupState after = observe();
            if (after.watermark() < before.rawMaxId()) {
                log.warn("LLM direct usage rollup still trails its starting raw event ID; "
                        + "possible lock contention: watermark={}, rawMaxId={}",
                        after.watermark(), before.rawMaxId());
            }
            if (after.stale()) {
                failClosed("LLM usage rollup success marker is stale");
                return;
            }
            if (lagExpired(after)) {
                failClosed("LLM usage rollup watermark has trailed raw events for 15 minutes");
                return;
            }
            lastObservedFreshAt.set(Instant.now(clock));
            log.info("LLM direct usage rollup refreshed: rebuiltDays={}, watermark={}, rawMaxId={}",
                    rebuilt, after.watermark(), after.rawMaxId());
        } catch (RuntimeException failure) {
            log.error("LLM direct usage rollup refresh failed: {}", failure.getClass().getSimpleName());
            failIfObservationExpired();
        }
    }

    void observeSafely() {
        if (fenced.get()) {
            return;
        }
        try {
            RollupState state = observe();
            if (state.stale()) {
                failClosed("LLM usage rollup success marker is stale");
                return;
            }
            if (lagExpired(state)) {
                failClosed("LLM usage rollup watermark has trailed raw events for 15 minutes");
                return;
            }
            lastObservedFreshAt.set(Instant.now(clock));
            if (state.rawMaxId() > state.watermark()) {
                log.info("LLM direct usage rollup has new raw events awaiting its next refresh: "
                        + "watermark={}, rawMaxId={}", state.watermark(), state.rawMaxId());
            }
        } catch (RuntimeException failure) {
            log.error("LLM direct usage rollup observation failed: {}",
                    failure.getClass().getSimpleName());
            failIfObservationExpired();
        }
    }

    private void failIfObservationExpired() {
        Instant last = lastObservedFreshAt.get();
        if (last == null) {
            failClosed("LLM usage rollup has no successful observation");
            return;
        }
        Duration age = Duration.between(last, Instant.now(clock));
        if (age.isNegative() || age.compareTo(MAX_SUCCESS_AGE) >= 0) {
            failClosed("LLM usage rollup could not be observed for 15 minutes");
        }
    }

    private RollupState observe() {
        return jdbcTemplate.queryForObject(STATE_SQL, (rs, row) -> {
            Timestamp success = rs.getTimestamp(3);
            return new RollupState(rs.getLong(1), rs.getLong(2),
                    success == null ? null : success.toInstant(), rs.getTimestamp(4).toInstant());
        });
    }

    private synchronized boolean lagExpired(RollupState state) {
        if (state.rawMaxId() <= state.watermark()) {
            pendingLag = null;
            return false;
        }
        Instant now = Instant.now(clock);
        if (pendingLag == null || state.watermark() >= pendingLag.targetId()) {
            pendingLag = new Lag(state.rawMaxId(), now);
            return false;
        }
        Duration age = Duration.between(pendingLag.firstObservedAt(), now);
        return age.isNegative() || age.compareTo(MAX_SUCCESS_AGE) >= 0;
    }

    private void failClosed(String reason) {
        if (fenced.compareAndSet(false, true)) {
            log.error("{}; stopping the direct rollup scheduler", reason);
            executor.shutdownNow();
            AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
        }
    }

    @PreDestroy
    @Override
    public void close() {
        executor.shutdownNow();
    }

    record RollupState(long watermark, long rawMaxId, Instant lastSuccessAt, Instant dbNow) {
        boolean stale() {
            if (lastSuccessAt == null) {
                return true;
            }
            Duration age = Duration.between(lastSuccessAt, dbNow);
            return age.isNegative() || age.compareTo(MAX_SUCCESS_AGE) >= 0;
        }
    }

    private record Lag(long targetId, Instant firstObservedAt) {
    }
}
