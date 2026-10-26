package kr.ac.pusan.pickle.observation;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validates the bounded parser vocabulary without retaining arbitrary labels or text. */
@Component
public class ObservationValidator {
    public record Definition(String kind, String unit, Set<String> labels) { }
    public record Sample(UUID sourceId, UUID epoch, long sequence, Instant observedAt,
            Instant reporterReceivedAt, JsonNode host, JsonNode inference) { }
    public static final Set<String> STATES = Set.of("OBSERVED", "UNKNOWN", "UNSUPPORTED", "NOT_REPORTED", "SOURCE_FAILED");
    private static final Set<String> HOST_STATES = Set.of("OBSERVED", "UNSUPPORTED", "NOT_REPORTED", "SOURCE_FAILED");
    public static final Set<String> HOST_KEYS = Set.of("cpu_load1", "memory_total_bytes", "memory_available_bytes", "swap_used_bytes",
            "gpu_utilization_percent", "gpu_temperature_celsius", "gpu_memory_total_bytes", "gpu_power_watts", "gpu_power_limit_watts");
    public static final Map<String, Definition> DEFINITIONS = definitions();
    private final Clock clock;
    private final JsonMapper strict = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(4096).maxNumberLength(128).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    public ObservationValidator(Clock clock) { this.clock = clock; }

    public Sample parse(byte[] bytes) {
        try {
            require(bytes.length <= ObservationLimits.MAX_BODY_BYTES);
            JsonNode root = strict.readTree(utf8(bytes));
            exact(root, "version", "sourceId", "epoch", "sequence", "observedAt", "reporterReceivedAt", "host", "inference");
            require(integer(root.get("version")) == 1);
            UUID source = uuid(root.get("sourceId")); UUID epoch = uuid(root.get("epoch"));
            long sequence = integer(root.get("sequence"));
            Instant observed = Instant.parse(text(root.get("observedAt")));
            Instant imported = Instant.parse(text(root.get("reporterReceivedAt")));
            require(observed.getNano() % 1_000_000 == 0 && imported.getNano() % 1_000_000 == 0);
            Instant now = clock.instant();
            require(!observed.isBefore(now.minusSeconds(90L * 86400))
                    && !observed.isAfter(now.plusSeconds(ObservationLimits.FUTURE_SKEW_SECONDS))
                    && !imported.isAfter(now.plusSeconds(ObservationLimits.FUTURE_SKEW_SECONDS))
                    && !imported.isBefore(observed.minusSeconds(ObservationLimits.FUTURE_SKEW_SECONDS)));
            host(root.get("host")); inference(root.get("inference"), source, observed);
            return new Sample(source, epoch, sequence, observed, imported, root.get("host"), root.get("inference"));
        } catch (ApiException e) { throw e; }
        catch (RuntimeException e) { throw invalid(); }
    }

    private static void host(JsonNode value) {
        exact(value, "state", "metrics"); require(HOST_STATES.contains(state(value.get("state"))));
        JsonNode metrics = value.get("metrics"); require(metrics != null && metrics.isObject() && metrics.size() <= HOST_KEYS.size());
        for (var entry : metrics.properties()) {
            require(HOST_KEYS.contains(entry.getKey())); JsonNode measurement = entry.getValue();
            exact(measurement, "state", "value"); String status = state(measurement.get("state")); require(HOST_STATES.contains(status));
            if ("OBSERVED".equals(status)) {
                double number = number(measurement.get("value"));
                require(!"gpu_utilization_percent".equals(entry.getKey()) || number <= 100);
                require("OBSERVED".equals(value.get("state").asText()));
            }
            else require(measurement.get("value").isNull());
        }
    }

    private static void inference(JsonNode value, UUID source, Instant observed) {
        exact(value, "version", "observedAt", "sourceState", "identity", "engines", "unsupportedMetricNames", "notReportedMetricNames");
        require(integer(value.get("version")) == 1);
        number(value.get("observedAt"));
        require(value.get("observedAt").decimalValue().multiply(java.math.BigDecimal.valueOf(1000))
                .compareTo(java.math.BigDecimal.valueOf(observed.toEpochMilli())) == 0);
        String status = state(value.get("sourceState")); JsonNode identity = value.get("identity");
        exact(identity, "sourceId", "bootId", "processId", "configId");
        require(source.toString().equals(text(identity.get("sourceId"))));
        for (String key : List.of("bootId", "processId", "configId")) if (!identity.get(key).isNull()) text(identity.get(key));
        names(value.get("unsupportedMetricNames"), false); names(value.get("notReportedMetricNames"), true);
        JsonNode engines = value.get("engines"); require(engines != null && engines.isArray() && engines.size() <= ObservationLimits.MAX_ENGINES);
        require("OBSERVED".equals(status) || engines.isEmpty());
        Set<String> groups = new HashSet<>(); int total = 0;
        for (JsonNode engine : engines) {
            exact(engine, "modelName", "engineId", "gauges", "counters", "histograms", "notReportedMetricNames");
            require(groups.add(text(engine.get("modelName")) + "\u0000" + text(engine.get("engineId"))));
            names(engine.get("notReportedMetricNames"), true);
            for (String kind : List.of("gauges", "counters", "histograms")) {
                JsonNode metrics = engine.get(kind); require(metrics.isArray()); Set<String> seen = new HashSet<>();
                for (JsonNode metric : metrics) {
                    require(++total <= ObservationLimits.MAX_SERIES);
                    metric(metric, kind); require(seen.add(metricKey(metric)));
                }
            }
        }
    }

    private static void metric(JsonNode value, String kind) {
        Set<String> keys = new HashSet<>(Set.of("name", "labels", "unit", "sampleAt", "typeDeclared"));
        keys.addAll("histograms".equals(kind) ? Set.of("bounds", "counts", "count", "sum", "createdAt") : Set.of("value"));
        if ("counters".equals(kind)) keys.add("createdAt"); exact(value, keys.toArray(String[]::new));
        String name = text(value.get("name")); Definition definition = DEFINITIONS.get(name);
        require(definition != null && definition.kind().equals(kind) && definition.unit().equals(text(value.get("unit"))));
        require(value.get("typeDeclared").isBoolean()); JsonNode labels = value.get("labels"); require(labels.isObject());
        for (var label : labels.properties()) { require(definition.labels().contains(label.getKey())); label(label.getValue()); }
        if (!value.get("sampleAt").isNull()) number(value.get("sampleAt"));
        if (!"gauges".equals(kind) && !value.get("createdAt").isNull()) number(value.get("createdAt"));
        if (!"histograms".equals(kind)) {
            double number = number(value.get("value"));
            if ("counters".equals(kind) || "requests".equals(definition.unit())) require(value.get("value").decimalValue().stripTrailingZeros().scale() <= 0);
            if ("ratio".equals(definition.unit())) require(value.get("value").decimalValue().compareTo(java.math.BigDecimal.ONE) <= 0);
            if ("state".equals(definition.unit())) require(number == 0 || number == 1);
            return;
        }
        JsonNode bounds = value.get("bounds"), counts = value.get("counts");
        require(bounds.isArray() && counts.isArray() && bounds.size() == counts.size()
                && bounds.size() >= 1 && bounds.size() <= ObservationLimits.MAX_BUCKETS);
        double last = -1; long count = -1;
        for (int i = 0; i < bounds.size(); i++) {
            if (i == bounds.size() - 1) require(bounds.get(i).isString() && "+Inf".equals(bounds.get(i).asText()));
            else { double next = number(bounds.get(i)); require(next > last); last = next; }
            long next = integer(counts.get(i)); require(next >= count); count = next;
        }
        require(integer(value.get("count")) == count); double sum = number(value.get("sum"));
        require(count != 0 || sum == 0);
        long[] cumulative = new long[counts.size()]; for (int i = 0; i < cumulative.length; i++) cumulative[i] = integer(counts.get(i));
        require(histogramFeasible(bounds, cumulative, count, sum));
    }

    static boolean histogramFeasible(JsonNode bounds, long[] counts, long count, double sum) {
        if (count < 0 || sum < 0 || count == 0 && sum != 0 || counts.length != bounds.size() || counts.length == 0 || counts[counts.length - 1] != count) return false;
        double lower = 0, upper = 0, previousBound = 0; long previousCount = 0; boolean unbounded = false;
        for (int i = 0; i < counts.length; i++) {
            long observations = counts[i] - previousCount; if (observations < 0) return false;
            lower += previousBound * observations;
            if (i == counts.length - 1) unbounded = observations > 0;
            else { double boundary = bounds.get(i).asDouble(); upper += boundary * observations; previousBound = boundary; }
            previousCount = counts[i];
        }
        double tolerance = 1e-9 * Math.max(1, Math.max(sum, Math.max(lower, upper)));
        return sum >= lower - tolerance && (unbounded || sum <= upper + tolerance);
    }
    private static String utf8(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw invalid(); }
    }

    private static void names(JsonNode value, boolean known) {
        require(value != null && value.isArray() && value.size() <= ObservationLimits.MAX_NAMES); Set<String> seen = new HashSet<>();
        for (JsonNode item : value) { String name = text(item); require(name.matches("[a-zA-Z_:][a-zA-Z0-9_:]*") && seen.add(name)); require(!known || DEFINITIONS.containsKey(name)); }
    }
    public static String metricKey(JsonNode value) {
        String name = value.get("name").asText(); Map<String, String> labels = new TreeMap<>();
        value.get("labels").properties().forEach(e -> labels.put(e.getKey(), e.getValue().toString()));
        return labels.isEmpty() ? name : name + "{" + labels.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(",")) + "}";
    }
    static void exact(JsonNode value, String... keys) {
        require(value != null && value.isObject()); Set<String> actual = new HashSet<>(); value.propertyNames().forEach(actual::add);
        require(actual.equals(Set.of(keys)));
    }
    static String text(JsonNode value) {
        require(value != null && value.isString()); String text = value.asText();
        require(!text.isEmpty() && text.codePoints().noneMatch(Character::isISOControl));
        try { require(StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text)).remaining() <= ObservationLimits.MAX_TEXT_BYTES); }
        catch (CharacterCodingException e) { throw invalid(); }
        return text;
    }
    private static void label(JsonNode value) {
        require(value != null && value.isString()); String text = value.asText();
        // PostgreSQL jsonb cannot represent NUL; do not rewrite its meaning.
        require(text.indexOf(0) < 0);
        try { require(StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text)).remaining() <= ObservationLimits.MAX_TEXT_BYTES); }
        catch (CharacterCodingException e) { throw invalid(); }
    }
    static String state(JsonNode value) { String text = text(value); require(STATES.contains(text)); return text; }
    static double number(JsonNode value) {
        require(value != null && value.isNumber()); double n = value.asDouble();
        require(Double.isFinite(n) && n >= 0 && n <= ObservationLimits.MAX_SAFE_INTEGER
                && (value.decimalValue().signum() == 0 || n != 0)); return n;
    }
    static long integer(JsonNode value) {
        require(value != null && value.isIntegralNumber() && value.canConvertToLong()); long n = value.asLong();
        require(n >= 0 && n <= ObservationLimits.MAX_SAFE_INTEGER); return n;
    }
    private static UUID uuid(JsonNode value) { String text = text(value); UUID id = UUID.fromString(text); require(id.toString().equals(text)); return id; }
    static void require(boolean condition) { if (!condition) throw invalid(); }
    static ApiException invalid() { return ApiException.validationFailed(List.of(new FieldValidationError("sample", "관측 표본의 형식과 범위가 올바르지 않습니다."))); }
    private static Map<String, Definition> definitions() {
        Map<String, Definition> result = new LinkedHashMap<>();
        for (String name : List.of("num_requests_running", "num_requests_waiting")) result.put("vllm:" + name, new Definition("gauges", "requests", Set.of()));
        result.put("vllm:num_requests_waiting_by_reason", new Definition("gauges", "requests", Set.of("reason")));
        result.put("vllm:engine_sleep_state", new Definition("gauges", "state", Set.of("sleep_state")));
        result.put("vllm:kv_cache_usage_perc", new Definition("gauges", "ratio", Set.of()));
        for (String name : List.of("prompt_tokens_total", "generation_tokens_total", "prompt_tokens_cached_total", "prefix_cache_queries_total", "prefix_cache_hits_total", "external_prefix_cache_queries_total", "external_prefix_cache_hits_total")) result.put("vllm:" + name, new Definition("counters", "tokens", Set.of()));
        result.put("vllm:num_preemptions_total", new Definition("counters", "preemptions", Set.of()));
        for (String name : List.of("mm_cache_queries_total", "mm_cache_hits_total")) result.put("vllm:" + name, new Definition("counters", "items", Set.of()));
        result.put("vllm:request_success_total", new Definition("counters", "requests", Set.of("finished_reason")));
        result.put("vllm:prompt_tokens_by_source_total", new Definition("counters", "tokens", Set.of("source")));
        for (String name : List.of("time_to_first_token_seconds", "inter_token_latency_seconds", "request_time_per_output_token_seconds", "e2e_request_latency_seconds", "request_queue_time_seconds", "request_inference_time_seconds", "request_prefill_time_seconds", "request_decode_time_seconds")) result.put("vllm:" + name, new Definition("histograms", "seconds", Set.of()));
        for (String name : List.of("request_prompt_tokens", "request_generation_tokens", "iteration_tokens_total", "request_max_num_generation_tokens", "request_params_max_tokens", "request_prefill_kv_computed_tokens")) result.put("vllm:" + name, new Definition("histograms", "tokens", Set.of()));
        result.put("vllm:request_params_n", new Definition("histograms", "sequences", Set.of()));
        return Map.copyOf(result);
    }
}
