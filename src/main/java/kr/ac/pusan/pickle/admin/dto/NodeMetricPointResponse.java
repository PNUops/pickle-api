package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import kr.ac.pusan.pickle.proxmox.dto.NodeRrdSample;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code NodeMetricPoint}. Nullable throughout for the same
 * reason as the VM series: an RRD row omits the keys it has no data for, and a
 * gap must stay a gap.
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

    public static NodeMetricPointResponse from(NodeRrdSample sample) {
        return new NodeMetricPointResponse(
                sample.time() == null ? null : Instant.ofEpochSecond(sample.time()),
                sample.cpu(),
                sample.iowait(),
                sample.loadavg(),
                bytes(sample.memtotal()),
                bytes(sample.memused()),
                bytes(sample.swaptotal()),
                bytes(sample.swapused()),
                bytes(sample.roottotal()),
                bytes(sample.rootused()),
                sample.netin(),
                sample.netout());
    }

    private static Long bytes(Double value) {
        return value == null ? null : Math.round(value);
    }
}
