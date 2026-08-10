package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import kr.ac.pusan.pickle.proxmox.RrdValues;
import kr.ac.pusan.pickle.proxmox.dto.NodeRrdSample;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code NodeMetricPoint}. Nullable throughout for the same
 * reason as the VM series: an RRD row omits the keys it has no data for, and a
 * gap must stay a gap.
 *
 * <p>{@code time} is the exception for the same reason it is on the VM series:
 * it is required by the contract and a point that cannot be placed on the axis
 * cannot be charted, so a timeless row is dropped where the series is mapped.
 */
public record NodeMetricPointResponse(
        Instant time,
        @Schema(description = "노드 전체 스레드를 1.0으로 본 사용률 (0~1)")
        @Nullable Double cpu,
        @Nullable Double iowait,
        @Nullable Double loadavg,
        @Nullable Long memTotalBytes,
        @Nullable Long memUsedBytes,
        @Nullable Long swapTotalBytes,
        @Nullable Long swapUsedBytes,
        @Nullable Long rootTotalBytes,
        @Schema(description = "노드 루트 파일시스템 사용량 — 게스트 디스크가 사는 thin pool은 "
                + "스토리지 상태 값으로 따로 제공")
        @Nullable Long rootUsedBytes,
        @Nullable Double netinBps,
        @Nullable Double netoutBps) {

    /** Callers drop timeless rows first (see the class note); everything else may be null. */
    public static NodeMetricPointResponse from(NodeRrdSample sample) {
        return new NodeMetricPointResponse(
                Instant.ofEpochSecond(sample.time()),
                sample.cpu(),
                sample.iowait(),
                sample.loadavg(),
                RrdValues.bytes(sample.memtotal()),
                RrdValues.bytes(sample.memused()),
                RrdValues.bytes(sample.swaptotal()),
                RrdValues.bytes(sample.swapused()),
                RrdValues.bytes(sample.roottotal()),
                RrdValues.bytes(sample.rootused()),
                sample.netin(),
                sample.netout());
    }
}
