package kr.ac.pusan.pickle.llm;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.jobrunr.server.BackgroundJobServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Runs only the existing LLM daily-quota refresh while JobRunr's shared worker
 * is disabled. The session lock rejects a second direct scheduler on the same
 * database for this process's lifetime; it cannot fence a different database.
 */
@Component
@ConditionalOnProperty(prefix = "pickle.llm.quota-direct", name = "enabled", havingValue = "true")
public class LlmDirectQuotaScheduler implements ApplicationRunner, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LlmDirectQuotaScheduler.class);
    private static final long LOCK_KEY = 0x504b4c4c4d51554fL;
    private static final Duration INTERVAL = Duration.ofMinutes(5);
    private static final Duration MAX_STALE = Duration.ofMinutes(15);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(30);

    private final DataSource dataSource;
    private final LlmQuotaService quotaService;
    private final Environment environment;
    private final ObjectProvider<BackgroundJobServer> backgroundServer;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration shutdownWait;
    private final ReentrantLock executionLock = new ReentrantLock();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "llm-direct-quota");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Connection ownerConnection;
    private volatile int ownerBackendPid;
    private volatile Instant lastSuccessAt;
    private volatile boolean closing;
    private volatile boolean fenced;
    private boolean started;

    @Autowired
    public LlmDirectQuotaScheduler(DataSource dataSource, LlmQuotaService quotaService,
            Environment environment, ObjectProvider<BackgroundJobServer> backgroundServer,
            ApplicationEventPublisher events, Clock clock) {
        this(dataSource, quotaService, environment, backgroundServer, events, clock, SHUTDOWN_WAIT);
    }

    LlmDirectQuotaScheduler(DataSource dataSource, LlmQuotaService quotaService,
            Environment environment, ObjectProvider<BackgroundJobServer> backgroundServer,
            ApplicationEventPublisher events, Clock clock, Duration shutdownWait) {
        this.dataSource = dataSource;
        this.quotaService = quotaService;
        this.environment = environment;
        this.backgroundServer = backgroundServer;
        this.events = events;
        this.clock = clock;
        this.shutdownWait = shutdownWait;
    }

    @Override
    public synchronized void run(org.springframework.boot.ApplicationArguments args) throws Exception {
        if (started) {
            throw new IllegalStateException("LLM direct quota scheduler was started twice");
        }
        if (environment.getProperty("jobrunr.background-job-server.enabled", Boolean.class, true)
                || backgroundServer.getIfAvailable() != null) {
            throw new IllegalStateException("LLM direct quota requires the JobRunr worker to be disabled");
        }
        Connection connection = dataSource.getConnection();
        boolean acquired = false;
        try {
            if (!connection.getAutoCommit()) {
                throw new IllegalStateException("LLM direct quota lock needs an autocommit session");
            }
            if (!tryLock(connection)) {
                throw new IllegalStateException("another LLM direct quota scheduler owns this database");
            }
            acquired = true;
            ownerBackendPid = backendPid(connection);
            ownerConnection = connection;
            refresh(); // Startup must prove the quota state before accepting traffic.
            lastSuccessAt = Instant.now(clock);
            executor.scheduleWithFixedDelay(this::refreshOnSchedule,
                    INTERVAL.toSeconds(), INTERVAL.toSeconds(), TimeUnit.SECONDS);
            started = true;
            log.info("LLM direct quota scheduler started with JobRunr worker disabled");
        } catch (Exception | Error failure) {
            executor.shutdownNow();
            ownerConnection = null;
            try {
                if (acquired) {
                    release(connection);
                } else {
                    connection.close();
                }
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    void refreshOnSchedule() {
        executionLock.lock();
        try {
            if (closing || fenced) {
                return;
            }
            try {
                refresh();
                lastSuccessAt = Instant.now(clock);
            } catch (SQLException ownershipFailure) {
                failClosed("LLM direct quota ownership is uncertain", ownershipFailure);
            } catch (RuntimeException refreshFailure) {
                Instant now = Instant.now(clock);
                Duration age = Duration.between(lastSuccessAt, now);
                if (age.isNegative() || age.compareTo(MAX_STALE) >= 0) {
                    failClosed("LLM direct quota has been stale for at least 15 minutes",
                            refreshFailure);
                } else {
                    log.error("LLM direct quota refresh failed; the next cycle will retry",
                            refreshFailure);
                }
            }
        } finally {
            executionLock.unlock();
        }
    }

    private void refresh() throws SQLException {
        ensureOwner();
        try {
            int changed = quotaService.refresh();
            log.info("LLM direct quota refresh completed; changed {} key(s)", changed);
        } finally {
            // Also check when refresh threw: a lost lease must never be mistaken
            // for a transient quota error while readiness stays accepting.
            ensureOwner();
        }
    }

    private void ensureOwner() throws SQLException {
        Connection connection = ownerConnection;
        if (connection == null || connection.isClosed()
                || backendPid(connection) != ownerBackendPid) {
            throw new SQLException("LLM direct quota lock session was lost");
        }
    }

    private void failClosed(String message, Throwable failure) {
        fenced = true;
        log.error("{}; stopping its scheduler", message, failure);
        executor.shutdownNow();
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
    }

    private void releaseOwnedConnection() throws SQLException {
        Connection connection = ownerConnection;
        if (connection != null) {
            try {
                release(connection);
            } finally {
                ownerConnection = null;
            }
        }
    }

    private static boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_backend_pid()")) {
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("LLM direct quota lock session has no backend");
                }
                return result.getInt(1);
            }
        }
    }

    private void release(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("select pg_advisory_unlock(?)")) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new SQLException("LLM direct quota lock was not held at release");
                }
            }
        } catch (SQLException failure) {
            // A pooled physical session must not retain an advisory lock.
            if (dataSource instanceof HikariDataSource hikari) {
                hikari.evictConnection(connection);
            } else {
                connection.abort(Runnable::run);
            }
            throw failure;
        } finally {
            connection.close();
        }
    }

    @PreDestroy
    @Override
    public synchronized void close() throws Exception {
        closing = true;
        executor.shutdownNow();
        boolean acquired;
        try {
            acquired = executionLock.tryLock(shutdownWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            // Never return from graceful shutdown while a refresh is still
            // running. Systemd may kill this process, which ends both its DB
            // transaction and advisory-lock session together.
            log.error("LLM direct quota refresh did not stop; waiting with its owner lock held");
            AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
            executionLock.lock();
        }
        try {
            releaseOwnedConnection();
        } finally {
            executionLock.unlock();
        }
    }
}
