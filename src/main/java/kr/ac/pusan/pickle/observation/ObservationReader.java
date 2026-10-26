package kr.ac.pusan.pickle.observation;

import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.InstitutionLlmAccess;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse.CapacityTelemetry;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse.CapacityTelemetryStatus;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.time.DatabaseTimestamps;
import kr.ac.pusan.pickle.observation.dto.InferenceObservationView;
import kr.ac.pusan.pickle.observation.dto.InferenceObservationView.InferenceObservationEngine;
import kr.ac.pusan.pickle.observation.dto.InferenceObservationView.InferenceObservationEvidence;
import kr.ac.pusan.pickle.observation.dto.InferenceObservationView.Freshness;
import kr.ac.pusan.pickle.observation.dto.InferenceObservationView.IntervalState;
import kr.ac.pusan.pickle.observation.dto.InferenceObservationView.InferenceObservationMetric;
import kr.ac.pusan.pickle.observation.dto.InferenceObservationView.InferenceObservationPoint;
import kr.ac.pusan.pickle.observation.dto.InferenceObservationView.RetentionState;
import kr.ac.pusan.pickle.observation.dto.InferenceObservationView.ValueState;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Streams cumulative samples into bounded statistics, never a raw history list. */
@Service
public class ObservationReader {
    private final ObservationProperties properties;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    public ObservationReader(ObservationProperties properties, JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.properties = properties; this.jdbc = jdbc; this.json = json; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CapacityTelemetry read(AuthenticatedUser actor, Instant from, Instant to, int bucket, @Nullable UUID requestedSource) {
        if (!InstitutionLlmAccess.systemRead(actor)) return new CapacityTelemetry(CapacityTelemetryStatus.NOT_PROVIDED, null);
        if (!properties.bound()) return new CapacityTelemetry(CapacityTelemetryStatus.NOT_CONFIGURED, null);
        if (requestedSource != null && !requestedSource.equals(properties.sourceId())) throw new ApiException(
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "관측 원천을 찾을 수 없습니다", "설정된 관측 원천을 확인해 주세요.");
        Instant now = clock.instant(), cutoff = now.minusSeconds(ObservationLimits.RETENTION_DAYS * 86400L);
        var latest = jdbc.query("""
                select upstream_id,upstream_name,observed_at from inference_observation_samples
                where source_id=? and observed_at>=? and received_at>=? order by observed_at desc,id desc limit 1
                """, (rs, n) -> new Header(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant()),
                properties.sourceId(), DatabaseTimestamps.ceilingMicros(cutoff), DatabaseTimestamps.ceilingMicros(cutoff));
        Instant received = jdbc.queryForObject("select max(received_at) from inference_observation_samples where source_id=? and observed_at>=? and received_at>=?",
                Instant.class, properties.sourceId(), DatabaseTimestamps.ceilingMicros(cutoff), DatabaseTimestamps.ceilingMicros(cutoff));
        Header header = latest.isEmpty() ? new Header(properties.upstreamId(), null, null) : latest.getFirst();
        Window window = new Window(from, to, bucket, cutoff, value -> json.writeValueAsBytes(value).length);
        if (window.retention != RetentionState.OUTSIDE) {
            Instant start = from.isBefore(cutoff) ? cutoff : from;
            jdbc.query(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        select producer_epoch,sample_sequence,observed_at,host,inference from inference_observation_samples
                        where source_id=? and observed_at>=? and observed_at<? and observed_at>=? and received_at>=?
                        order by producer_epoch,sample_sequence limit ?
                        """);
                statement.setObject(1, properties.sourceId()); statement.setTimestamp(2, DatabaseTimestamps.ceilingMicros(start));
                statement.setTimestamp(3, DatabaseTimestamps.ceilingMicros(to)); statement.setTimestamp(4, DatabaseTimestamps.ceilingMicros(cutoff));
                statement.setTimestamp(5, DatabaseTimestamps.ceilingMicros(cutoff)); statement.setInt(6, ObservationLimits.MAX_READ_ROWS + 1);
                statement.setFetchSize(ObservationLimits.FETCH_SIZE); return statement;
            }, (ResultSetExtractor<Void>) rs -> {
                while (rs.next()) {
                    if (window.samples >= ObservationLimits.MAX_READ_ROWS) { window.truncated = true; break; }
                    window.accept(new Sample(rs.getObject(1, UUID.class), rs.getLong(2), rs.getTimestamp(3).toInstant(),
                            json.readTree(rs.getString(4)), json.readTree(rs.getString(5))));
                }
                return null;
            });
        }
        Freshness freshness = header.observed == null ? Freshness.NOT_REPORTED
                : header.observed.isBefore(now.minusSeconds(ObservationLimits.STALE_SECONDS)) ? Freshness.STALE : Freshness.FRESH;
        boolean outside = window.retention == RetentionState.OUTSIDE;
        long nanos = outside ? 0 : Duration.between(from.isBefore(cutoff) ? cutoff : from, to).toNanos();
        long expected = outside ? 0 : (nanos - 1) / 60_000_000_000L + 1;
        InferenceObservationEvidence evidence = new InferenceObservationEvidence(cutoff, window.retention, header.observed, received, freshness,
                outside ? null : window.samples, outside ? null : expected, outside ? null : window.failed,
                outside ? null : window.baselines, outside ? null : window.resets, outside ? null : window.gaps, window.truncated);
        var device = new InferenceObservationView(properties.sourceId(), header.upstream, header.name, from, to, bucket,
                ObservationLimits.SAMPLE_SECONDS, ObservationLimits.RETENTION_DAYS, evidence, window.finish());
        // The structural budget is conservative; keep an independent serialized cap.
        if (json.writeValueAsBytes(device).length > ObservationLimits.MAX_RESPONSE_BYTES) {
            evidence = new InferenceObservationEvidence(cutoff, window.retention, header.observed, received, freshness,
                    outside ? null : window.samples, outside ? null : expected, outside ? null : window.failed,
                    outside ? null : window.baselines, outside ? null : window.resets, outside ? null : window.gaps, true);
            device = new InferenceObservationView(properties.sourceId(), header.upstream, header.name, from, to, bucket,
                    ObservationLimits.SAMPLE_SECONDS, ObservationLimits.RETENTION_DAYS, evidence, List.of());
        }
        return new CapacityTelemetry(window.samples == 0 ? CapacityTelemetryStatus.NOT_REPORTED : CapacityTelemetryStatus.REPORTED, device);
    }

    private record Header(UUID upstream, @Nullable String name, @Nullable Instant observed) { }
    private record Sample(UUID epoch, long sequence, Instant at, JsonNode host, JsonNode inference) { }
    private record EngineKey(String model, String engine, UUID epoch, @Nullable String boot, @Nullable String process, @Nullable String config) { }
    private static @Nullable String optional(JsonNode node, String key) { JsonNode value = node.get(key); return value == null || value.isNull() ? null : value.asText(); }
    private static EngineKey key(Sample sample, JsonNode engine) {
        JsonNode identity = sample.inference.get("identity");
        return new EngineKey(engine.get("modelName").asText(), engine.get("engineId").asText(), sample.epoch,
                optional(identity, "bootId"), optional(identity, "processId"), optional(identity, "configId"));
    }
    private static RetentionState retention(Instant from, Instant to, Instant cutoff) {
        return !to.isAfter(cutoff) ? RetentionState.OUTSIDE : from.isBefore(cutoff) ? RetentionState.PARTIAL : RetentionState.WITHIN;
    }
    private static Map<String, JsonNode> metrics(JsonNode group, String kind) {
        Map<String, JsonNode> result = new HashMap<>();
        group.get(kind).forEach(item -> result.put(ObservationValidator.metricKey(item), item)); return result;
    }

    private static final class Accumulator {
        ValueState state = ValueState.NOT_REPORTED;
        double numerator, denominator, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, intervalSeconds;
        long observed;
        void missing(String reported) { if (observed == 0) state = ValueState.valueOf(reported); }
        void sample(double value) {
            if (value == 0) value = 0;
            state = ValueState.OBSERVED; numerator += value; denominator++; observed++;
            min = Math.min(min, value); max = Math.max(max, value);
        }
        void interval(double sum, double count, double seconds) {
            state = ValueState.OBSERVED; numerator += sum; denominator += count; intervalSeconds += seconds; observed++;
            if (count > 0) { double value = sum / count; min = Math.min(min, value); max = Math.max(max, value); }
        }
        InferenceObservationMetric finish() { return new InferenceObservationMetric(state, denominator == 0 ? null : numerator / denominator,
                Double.isFinite(min) ? min : null, Double.isFinite(max) ? max : null, observed, intervalSeconds); }
    }
    private static Map<String, InferenceObservationMetric> finish(Map<String, Accumulator> values) {
        Map<String, InferenceObservationMetric> result = new LinkedHashMap<>(); values.forEach((key, value) -> result.put(key, value.finish())); return result;
    }
    private static final class EngineStats {
        final EngineKey key;
        long valid, baseline, reset, gap;
        final Map<String, Accumulator> gauges = new LinkedHashMap<>(), rates = new LinkedHashMap<>(), histograms = new LinkedHashMap<>();
        EngineStats(EngineKey key) { this.key = key; }
        InferenceObservationEngine finish() {
            IntervalState state = valid > 0 ? baseline + reset + gap > 0 ? IntervalState.PARTIAL : IntervalState.VALID : IntervalState.BASELINE;
            return new InferenceObservationEngine(key.model, key.engine, key.epoch, key.boot, key.process, key.config, state,
                    valid, baseline, reset, gap, ObservationReader.finish(gauges), ObservationReader.finish(rates), ObservationReader.finish(histograms));
        }
    }
    private static final class Bucket {
        final Instant from, to;
        final RetentionState retention;
        long samples, failed;
        final Map<String, Accumulator> host = new LinkedHashMap<>();
        final Map<EngineKey, EngineStats> engines = new LinkedHashMap<>();
        Bucket(Instant from, Instant to, Instant cutoff) { this.from = from; this.to = to; retention = retention(from, to, cutoff); }
        InferenceObservationPoint finish() { return new InferenceObservationPoint(from, to, retention, retention == RetentionState.OUTSIDE ? null : samples,
                retention == RetentionState.OUTSIDE ? null : failed, ObservationReader.finish(host), engines.values().stream().map(EngineStats::finish).toList()); }
    }

    private static final class Window {
        final Instant from, to;
        final Instant admittedFrom;
        final int step;
        final java.util.function.ToIntFunction<String> encodedBytes;
        final RetentionState retention;
        final List<Bucket> buckets = new ArrayList<>();
        final Map<EngineKey, Boolean> identities = new HashMap<>();
        @Nullable Sample previous;
        long samples, failed, baselines, resets, gaps;
        int entries;
        int outputBudget;
        boolean truncated;
        Window(Instant from, Instant to, int step, Instant cutoff, java.util.function.ToIntFunction<String> encodedBytes) {
            this.from = from; this.to = to; admittedFrom = from.isBefore(cutoff) ? cutoff : from;
            this.step = step; retention = retention(from, to, cutoff);
            this.encodedBytes = encodedBytes;
            ObservationValidator.require(step >= 60 && step <= 86400 && from.isBefore(to));
            for (Instant at = from; at.isBefore(to); at = at.plusSeconds(step)) {
                ObservationValidator.require(buckets.size() < 1440);
                buckets.add(new Bucket(at, at.plusSeconds(step).isAfter(to) ? to : at.plusSeconds(step), cutoff));
            }
            outputBudget = 4096 + buckets.size() * 300;
        }
        int index(Instant at) { return (int) Math.min(buckets.size() - 1, Math.max(0, Duration.between(from, at).toMillis() / (step * 1000L))); }
        @Nullable Accumulator acc(Map<String, Accumulator> map, String name) {
            Accumulator value = map.get(name);
            if (value != null) return value;
            if (entries >= ObservationLimits.MAX_OUTPUT_METRICS || !reserve(encodedBytes.applyAsInt(name) + 256)) { truncated = true; return null; }
            value = new Accumulator(); map.put(name, value); entries++; return value;
        }
        @Nullable EngineStats engine(Bucket bucket, EngineKey key) {
            EngineStats existing = bucket.engines.get(key); if (existing != null) return existing;
            if (!identities.containsKey(key)) {
                if (identities.size() >= ObservationLimits.MAX_ENGINES) { truncated = true; return null; }
                identities.put(key, true);
            }
            int bytes = 512 + encodedBytes.applyAsInt(key.model) + encodedBytes.applyAsInt(key.engine)
                    + encodedBytes.applyAsInt(key.epoch.toString()) + encodedBytes.applyAsInt(key.boot)
                    + encodedBytes.applyAsInt(key.process) + encodedBytes.applyAsInt(key.config);
            if (!reserve(bytes)) return null;
            EngineStats value = new EngineStats(key); bucket.engines.put(key, value); return value;
        }
        boolean reserve(int bytes) {
            if (outputBudget + bytes > ObservationLimits.MAX_RESPONSE_BYTES) { truncated = true; return false; }
            outputBudget += bytes; return true;
        }
        void accept(Sample current) {
            samples++; Bucket bucket = buckets.get(index(current.at)); bucket.samples++;
            if ("SOURCE_FAILED".equals(current.host.get("state").asText()) || "SOURCE_FAILED".equals(current.inference.get("sourceState").asText())) { failed++; bucket.failed++; }
            for (var item : current.host.get("metrics").properties()) {
                Accumulator value = acc(bucket.host, item.getKey()); if (value == null) continue;
                String state = item.getValue().get("state").asText();
                if ("OBSERVED".equals(state)) value.sample(item.getValue().get("value").asDouble()); else value.missing(state);
            }
            Sample old = previous != null && previous.epoch.equals(current.epoch) ? previous : null;
            Map<String, JsonNode> oldEngines = new HashMap<>();
            if (old != null) old.inference.get("engines").forEach(group -> oldEngines.put(group.get("modelName").asText() + "\u0000" + group.get("engineId").asText(), group));
            int currentEngineCount = current.inference.get("engines").size();
            for (JsonNode group : current.inference.get("engines")) {
                EngineKey key = key(current, group); EngineStats stats = engine(bucket, key); if (stats == null) continue;
                for (JsonNode metric : group.get("gauges")) {
                    Instant measured = metric.get("sampleAt").isNull() ? current.at : epoch(metric.get("sampleAt"));
                    boolean inside = !measured.isBefore(admittedFrom) && measured.isBefore(to);
                    EngineStats target = inside ? engine(buckets.get(index(measured)), key) : stats;
                    if (target == null) continue;
                    Accumulator a = acc(target.gauges, ObservationValidator.metricKey(metric));
                    if (a != null) { if (inside) a.sample(metric.get("value").asDouble()); else a.missing("UNKNOWN"); }
                }
                for (JsonNode metric : group.get("counters")) acc(stats.rates, ObservationValidator.metricKey(metric));
                for (JsonNode metric : group.get("histograms")) acc(stats.histograms, ObservationValidator.metricKey(metric));
                JsonNode before = oldEngines.remove(key.model + "\u0000" + key.engine);
                double elapsed = old == null ? 0 : Duration.between(old.at, current.at).toNanos() / 1e9;
                boolean complete = key.boot != null && key.process != null && key.config != null;
                boolean same = old != null && before != null && key.equals(key(old, before));
                if (old == null || before == null || !complete || !same || elapsed <= 0 || elapsed > ObservationLimits.MAX_GAP_SECONDS) {
                    stats.baseline++; baselines++;
                    if (old != null && ((!same && before != null) || elapsed <= 0)) { stats.reset++; resets++; }
                    if (elapsed > ObservationLimits.MAX_GAP_SECONDS) { stats.gap++; gaps++; }
                    continue;
                }
                boolean any = false, partial = false, reset = false;
                Set<EngineStats> validStats = new HashSet<>();
                for (String kind : List.of("counters", "histograms")) {
                    Map<String, JsonNode> preceding = metrics(before, kind);
                    for (JsonNode metric : group.get(kind)) {
                        String name = ObservationValidator.metricKey(metric); JsonNode prior = preceding.remove(name);
                        if (prior == null) { partial = true; continue; }
                        if (!sameNumber(prior.get("createdAt"), metric.get("createdAt"))
                                || prior.get("sampleAt").isNull() != metric.get("sampleAt").isNull()
                                || "histograms".equals(kind) && !sameBounds(prior.get("bounds"), metric.get("bounds"))) { reset = true; continue; }
                        Instant intervalStart = prior.get("sampleAt").isNull() ? old.at : epoch(prior.get("sampleAt"));
                        Instant intervalEnd = metric.get("sampleAt").isNull() ? current.at : epoch(metric.get("sampleAt"));
                        Duration duration = Duration.between(intervalStart, intervalEnd);
                        if (duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofSeconds(ObservationLimits.MAX_GAP_SECONDS)) > 0) { reset = true; continue; }
                        double seriesElapsed = duration.toNanos() / 1e9;
                        double numerator, denominator;
                        if ("counters".equals(kind)) { numerator = metric.get("value").asDouble() - prior.get("value").asDouble(); denominator = seriesElapsed; }
                        else {
                            numerator = metric.get("sum").asDouble() - prior.get("sum").asDouble();
                            denominator = metric.get("count").asDouble() - prior.get("count").asDouble();
                            long lastDelta = -1; long[] deltas = new long[metric.get("counts").size()];
                            for (int i = 0; i < metric.get("counts").size(); i++) {
                                long delta = metric.get("counts").get(i).asLong() - prior.get("counts").get(i).asLong();
                                if (delta < lastDelta || delta < 0) { reset = true; numerator = -1; break; }
                                lastDelta = delta; deltas[i] = delta;
                            }
                            if (!ObservationValidator.histogramFeasible(metric.get("bounds"), deltas, (long) denominator, numerator)) { reset = true; numerator = -1; }
                        }
                        if (numerator < 0 || denominator < 0) { reset = true; continue; }
                        any |= distribute(intervalStart, intervalEnd, seriesElapsed, key, kind, name, numerator, denominator, validStats);
                    }
                    if (!preceding.isEmpty()) partial = true;
                }
                validStats.forEach(s -> s.valid++);
                if (!any) { stats.baseline++; baselines++; }
                if (partial && any) {
                    stats.baseline++; validStats.stream().filter(s -> s != stats).forEach(s -> s.baseline++); baselines++;
                }
                if (reset) { stats.reset++; validStats.stream().filter(s -> s != stats).forEach(s -> s.reset++); resets++; }
            }
            if (currentEngineCount == 0 && oldEngines.isEmpty()) baselines++;
            for (JsonNode missing : oldEngines.values()) {
                EngineStats missingStats = engine(bucket, key(old, missing));
                if (missingStats != null) missingStats.baseline++;
                baselines++;
            }
            previous = current;
        }
        boolean distribute(Instant start, Instant end, double elapsed, EngineKey key, String kind, String name, double sum, double count, Set<EngineStats> validStats) {
            if (start.isBefore(admittedFrom) || !end.isBefore(to)) return false;
            EngineStats stats = engine(buckets.get(index(end)), key); if (stats == null) return false;
            Accumulator value = acc("counters".equals(kind) ? stats.rates : stats.histograms, name);
            if (value == null) return false;
            value.interval(sum, count, elapsed); validStats.add(stats); return true;
        }
        List<InferenceObservationPoint> finish() { return buckets.stream().map(Bucket::finish).toList(); }
    }
    private static boolean sameNumber(JsonNode a, JsonNode b) {
        return a.isNull() && b.isNull() || a.isNumber() && b.isNumber() && Double.compare(a.asDouble(), b.asDouble()) == 0;
    }
    private static boolean sameBounds(JsonNode a, JsonNode b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!(sameNumber(a.get(i), b.get(i)) || a.get(i).equals(b.get(i)))) return false;
        return true;
    }
    private static Instant epoch(JsonNode value) {
        java.math.BigDecimal number = value.decimalValue(); long seconds = number.longValue();
        return Instant.ofEpochSecond(seconds, number.subtract(java.math.BigDecimal.valueOf(seconds)).movePointRight(9).longValue());
    }
}
