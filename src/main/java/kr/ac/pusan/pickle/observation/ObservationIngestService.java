package kr.ac.pusan.pickle.observation;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.time.DatabaseTimestamps;
import kr.ac.pusan.pickle.observation.dto.InferenceObservationAck;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores one snapshot row, not its original HTTP body or an environment inventory. */
@Service
public class ObservationIngestService {
    private final ObservationProperties properties;
    private final ObservationValidator validator;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public ObservationIngestService(ObservationProperties properties, ObservationValidator validator, JdbcTemplate jdbc, Clock clock) {
        this.properties = properties; this.validator = validator; this.jdbc = jdbc; this.clock = clock;
    }
    @Transactional
    public InferenceObservationAck ingest(byte[] bytes) {
        ObservationValidator.Sample sample = validator.parse(bytes);
        if (!properties.configured() || !sample.sourceId().equals(properties.sourceId())) throw forbidden();
        String digest = hash(bytes);
        // The two-key advisory namespace does not collide with single-key locks.
        jdbc.queryForList("select pg_advisory_xact_lock(?,?)", 0x49505331, sample.sourceId().hashCode());
        var existing = jdbc.query("select content_sha256,received_at from inference_observation_samples where source_id=? and producer_epoch=? and sample_sequence=?",
                (rs, n) -> new InferenceObservationAck(sample.sourceId(), sample.epoch(), sample.sequence(), "DUPLICATE", rs.getString(1), rs.getTimestamp(2).toInstant()), sample.sourceId(), sample.epoch(), sample.sequence());
        if (!existing.isEmpty()) { if (!existing.getFirst().contentSha256().equals(digest)) throw conflict(); return existing.getFirst(); }
        long minute = Math.floorDiv(sample.observedAt().getEpochSecond(), 60);
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from inference_observation_samples where source_id=? and observed_minute=?)", Boolean.class, sample.sourceId(), minute))) throw conflict();
        Instant cutoff = clock.instant().minusSeconds(ObservationLimits.RETENTION_DAYS * 86400L);
        List<UUID> bindings = jdbc.query("select distinct upstream_id from inference_observation_samples where source_id=? and observed_at>=? and received_at>=?",
                (rs, n) -> rs.getObject(1, UUID.class), sample.sourceId(), DatabaseTimestamps.ceilingMicros(cutoff), DatabaseTimestamps.ceilingMicros(cutoff));
        if (bindings.stream().anyMatch(id -> !id.equals(properties.upstreamId()))) throw unavailable();
        List<String> names = jdbc.query("select display_name from llm_upstreams where public_id=?", (rs, n) -> rs.getString(1), properties.upstreamId());
        if (names.size() != 1) throw unavailable();
        String name = names.getFirst();
        if (name.isEmpty() || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > ObservationLimits.MAX_TEXT_BYTES
                || name.codePoints().anyMatch(Character::isISOControl)) throw unavailable();
        Instant received = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("""
                insert into inference_observation_samples(source_id,producer_epoch,sample_sequence,observed_at,observed_minute,
                    reporter_received_at,received_at,upstream_id,upstream_name,content_sha256,host,inference)
                values (?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                """, sample.sourceId(), sample.epoch(), sample.sequence(), Timestamp.from(sample.observedAt()), minute,
                Timestamp.from(sample.reporterReceivedAt()), Timestamp.from(received), properties.upstreamId(), name, digest,
                sample.host().toString(), sample.inference().toString());
        return new InferenceObservationAck(sample.sourceId(), sample.epoch(), sample.sequence(), "ACCEPTED", digest, received);
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }
    private static ApiException conflict() { return new ApiException(HttpStatus.CONFLICT, "INFERENCE_OBSERVATION_CONFLICT", "관측 표본이 충돌합니다", "동일 표본 identity와 분의 원문을 확인해 주세요."); }
    private static ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "접근 권한이 없습니다", "허용된 관측 보고 원천이 아닙니다."); }
    private static ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INFERENCE_OBSERVATION_UNAVAILABLE", "관측 보고 설정을 확인해 주세요", "서버의 고정 공급원 연결을 확인해 주세요."); }
}
