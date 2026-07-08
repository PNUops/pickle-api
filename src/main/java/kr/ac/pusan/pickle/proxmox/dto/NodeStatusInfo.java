package kr.ac.pusan.pickle.proxmox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code GET /nodes/{n}/status} response, reduced to what node placement
 * scoring needs (docs/plan/03): CPU capacity and memory headroom.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NodeStatusInfo(CpuInfo cpuinfo, MemoryInfo memory) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CpuInfo(int cpus, int cores, int sockets, String model) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MemoryInfo(long total, long used, long free, long available) {
    }
}
