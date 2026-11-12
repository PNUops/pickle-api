package kr.ac.pusan.pickle.observation;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Typed, bounded reports bind every observed asset to the configured source roster. */
@Component
public class PlatformHardwareValidator {
    public static final int MAX_BODY_BYTES = 64 * 1024;
    private final ObjectMapper mapper;
    private final PlatformHardwareProperties properties;
    private final Clock clock;
    public PlatformHardwareValidator(ObjectMapper mapper, PlatformHardwareProperties properties, Clock clock) {
        this.mapper = mapper; this.properties = properties; this.clock = clock;
    }
    PlatformHardwareReport parse(byte[] bytes) {
        if (bytes.length > MAX_BODY_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                ErrorCodes.VALIDATION_FAILED, "요청 본문이 너무 커요", "하드웨어 보고는 64KiB 이하여야 해요.");
        try {
            PlatformHardwareReport report = mapper.readerFor(PlatformHardwareReport.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(bytes);
            return validate(report);
        } catch (ApiException exception) { throw exception; }
        catch (RuntimeException exception) { throw invalid(); }
    }
    PlatformHardwareReport validate(PlatformHardwareReport report) {
        require(report != null && report.schemaVersion() == 1 && report.sourceId() != null
                && report.observedAt() != null && report.assets() != null
                && !report.assets().isEmpty() && report.assets().size() <= 32);
        if (!properties.configured() || !report.sourceId().equals(properties.sourceId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없어요", "허용된 장비 관측 보고 원천이 아니에요.");
        }
        Instant now = clock.instant();
        require(!report.observedAt().isAfter(now));
        require(!report.observedAt().isBefore(now.minus(properties.freshness())));
        Set<String> ids = new HashSet<>();
        long memoryTotal = 0;
        long dedicatedMemoryTotal = 0;
        for (var asset : report.assets()) {
            require(asset != null && validId(asset.id()) && ids.add(asset.id())
                    && text(asset.displayModel()) && text(asset.cpuModel())
                    && asset.cores() > 0 && asset.threads() > 0 && asset.memoryMiB() > 0
                    && asset.gpus() != null && asset.gpus().size() <= 16);
            memoryTotal = Math.addExact(memoryTotal, asset.memoryMiB());
            for (var gpu : asset.gpus()) {
                require(gpu != null && text(gpu.model()) && gpu.count() != null && gpu.count() >= 0
                        && positive(gpu.dedicatedMemoryMiB()) && positive(gpu.unifiedMemoryMiB())
                        && !(gpu.dedicatedMemoryMiB() != null && gpu.unifiedMemoryMiB() != null));
                if (gpu.dedicatedMemoryMiB() != null) {
                    dedicatedMemoryTotal = Math.addExact(dedicatedMemoryTotal,
                            Math.multiplyExact(gpu.dedicatedMemoryMiB(), gpu.count().longValue()));
                }
            }
        }
        require(ids.equals(Set.copyOf(properties.expectedAssetIds())));
        return new PlatformHardwareReport(1, report.sourceId(), report.observedAt().truncatedTo(ChronoUnit.MICROS),
                List.copyOf(report.assets()));
    }
    static boolean validId(@Nullable String id) { return id != null && id.matches("[A-Za-z0-9._-]{1,64}"); }
    private static boolean text(@Nullable String text) {
        return text != null && !text.isBlank() && text.codePointCount(0, text.length()) <= 256
                && text.codePoints().noneMatch(Character::isISOControl);
    }
    private static boolean positive(@Nullable Long value) { return value == null || value > 0; }
    private static void require(boolean condition) { if (!condition) throw invalid(); }
    private static ApiException invalid() {
        return ApiException.validationFailed(List.of(new FieldValidationError("report",
                "허용된 원천과 장비 목록, 실제 측정 시각과 사양을 확인해 주세요.")));
    }
}
