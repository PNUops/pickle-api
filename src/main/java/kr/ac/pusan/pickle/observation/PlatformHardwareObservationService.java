package kr.ac.pusan.pickle.observation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminPerformanceOverviewResponse.PerformanceOverviewAsset;
import kr.ac.pusan.pickle.admin.dto.AdminPerformanceOverviewResponse.PerformanceOverviewGpu;
import kr.ac.pusan.pickle.admin.dto.AdminPerformanceOverviewResponse.PerformanceOverviewHardware;
import kr.ac.pusan.pickle.common.error.ApiException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Latest-only automatic observations; no host access or configured capacity values. */
@Service
public class PlatformHardwareObservationService {
    private static final Logger log = LoggerFactory.getLogger(PlatformHardwareObservationService.class);
    private final PlatformHardwareProperties properties;
    private final PlatformHardwareValidator validator;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PlatformHardwareObservationService(PlatformHardwareProperties properties,
            PlatformHardwareValidator validator, JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.properties = properties; this.validator = validator; this.jdbc = jdbc;
        this.mapper = mapper; this.clock = clock;
    }

    @Transactional
    public PlatformHardwareAck ingest(byte[] bytes) {
        PlatformHardwareReport report = validator.parse(bytes);
        String assets = mapper.writeValueAsString(report.assets());
        int changed = jdbc.update("""
                insert into platform_hardware_observations(source_id,schema_version,observed_at,received_at,assets)
                values(?,1,?,?,?::jsonb)
                on conflict(source_id) do update
                   set schema_version=excluded.schema_version,observed_at=excluded.observed_at,
                       received_at=excluded.received_at,assets=excluded.assets
                 where platform_hardware_observations.observed_at < excluded.observed_at
                """, report.sourceId(), Timestamp.from(report.observedAt()),
                Timestamp.from(clock.instant()), assets);
        if (changed == 0) throw new ApiException(HttpStatus.CONFLICT, "HARDWARE_OBSERVATION_CONFLICT",
                "더 최근의 장비 보고가 이미 있어요", "이전 시각이나 같은 시각의 보고는 덮어쓰지 않아요.");
        return new PlatformHardwareAck(report.sourceId(), report.observedAt());
    }

    /** A metadata read failure never joins or aborts the business-statistics transaction. */
    public @Nullable PerformanceOverviewHardware current() {
        if (!properties.configured()) return null;
        try {
            List<StoredHardwareReport> rows = jdbc.query("""
                    select schema_version,observed_at,assets::text
                      from platform_hardware_observations where source_id=?
                    """, (rs, n) -> new StoredHardwareReport(rs.getInt(1), rs.getTimestamp(2).toInstant(),
                            rs.getString(3)), properties.sourceId());
            if (rows.isEmpty()) return null;
            StoredHardwareReport stored = rows.getFirst();
            Instant now = clock.instant();
            if (stored.observedAt().isAfter(now)
                    || stored.observedAt().isBefore(now.minus(properties.freshness()))) return null;
            List<PlatformHardwareReport.PlatformHardwareAssetReport> assets = mapper.readerFor(new TypeReference<List<PlatformHardwareReport.PlatformHardwareAssetReport>>() { })
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .readValue(stored.assets());
            PlatformHardwareReport report = validator.validate(new PlatformHardwareReport(stored.version(),
                    properties.sourceId(), stored.observedAt(), assets));
            return project(report);
        } catch (RuntimeException exception) {
            log.warn("Automatic hardware observation is unavailable; performance hardware is omitted");
            return null;
        }
    }

    private static PerformanceOverviewHardware project(PlatformHardwareReport report) {
        List<PerformanceOverviewAsset> assets = report.assets().stream().map(asset ->
                new PerformanceOverviewAsset(asset.displayModel(), asset.cpuModel(), asset.cores(),
                        asset.threads(), asset.memoryMiB(), asset.gpus().stream().map(gpu ->
                                new PerformanceOverviewGpu(gpu.model(), gpu.count(), gpu.dedicatedMemoryMiB(),
                                        gpu.unifiedMemoryMiB(), gpu.unifiedMemoryMiB() != null ? Boolean.TRUE
                                                : gpu.dedicatedMemoryMiB() != null ? Boolean.FALSE : null)).toList())).toList();
        List<PerformanceOverviewGpu> gpus = assets.stream().flatMap(asset -> asset.gpus().stream()).toList();
        boolean memoryKnown = gpus.stream().allMatch(gpu -> gpu.count() == 0
                || gpu.dedicatedMemoryMiB() != null || gpu.unifiedMemoryMiB() != null);
        Long dedicatedMemory = memoryKnown ? gpus.stream().mapToLong(gpu ->
                gpu.dedicatedMemoryMiB() == null ? 0 : gpu.dedicatedMemoryMiB() * gpu.count()).sum() : null;
        return new PerformanceOverviewHardware(assets.size(),
                assets.stream().mapToLong(PerformanceOverviewAsset::cpuCores).sum(),
                assets.stream().mapToLong(PerformanceOverviewAsset::cpuThreads).sum(),
                assets.stream().mapToLong(PerformanceOverviewAsset::memoryMiB).sum(),
                gpus.stream().mapToLong(PerformanceOverviewGpu::count).sum(), dedicatedMemory, assets);
    }

    public record PlatformHardwareAck(UUID sourceId, Instant observedAt) { }
    private record StoredHardwareReport(int version, Instant observedAt, String assets) { }
}
