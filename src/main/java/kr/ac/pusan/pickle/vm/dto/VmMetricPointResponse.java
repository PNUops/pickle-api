package kr.ac.pusan.pickle.vm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import kr.ac.pusan.pickle.proxmox.RrdValues;
import kr.ac.pusan.pickle.proxmox.dto.VmRrdSample;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code VmMetricPoint}. Every metric is nullable because an
 * RRD row omits the keys for intervals the VM was not running in, and that
 * absence is the honest gap a chart must draw rather than a zero.
 *
 * <p>{@code time} is the exception: it is the point's position on the axis and
 * the contract's only required field here, so a row that arrives without one
 * is dropped where the series is mapped rather than shipped as a null the
 * console would have to guess about.
 */
public record VmMetricPointResponse(
        Instant time,
        @Schema(description = "할당된 vCPU 전체를 1.0으로 본 사용률 (0~1)")
        @Nullable Double cpu,
        @Schema(description = "게스트 내부 기준 메모리 사용량(바이트). 게스트 에이전트가 보고하지 않으면 "
                + "하이퍼바이저 관점 값으로 대체됩니다.")
        @Nullable Long memBytes,
        @Schema(description = "하이퍼바이저 관점 메모리 사용량(바이트) — 게스트 페이지 캐시 포함.")
        @Nullable Long memHostBytes,
        @Nullable Long maxmemBytes,
        @Nullable Double netinBps,
        @Nullable Double netoutBps,
        @Nullable Double diskReadBps,
        @Nullable Double diskWriteBps) {

    /** Callers drop timeless rows first (see the class note); everything else may be null. */
    public static VmMetricPointResponse from(VmRrdSample sample) {
        return new VmMetricPointResponse(
                Instant.ofEpochSecond(sample.time()),
                sample.cpu(),
                RrdValues.bytes(sample.mem()),
                RrdValues.bytes(sample.memhost()),
                RrdValues.bytes(sample.maxmem()),
                sample.netin(),
                sample.netout(),
                sample.diskread(),
                sample.diskwrite());
    }
}
