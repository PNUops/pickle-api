package kr.ac.pusan.pickle.proxmox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code GET /nodes/{n}/status} response, reduced to what node placement
 * scoring needs — CPU capacity and memory headroom — plus the instantaneous
 * {@code cpu} load the live dashboard tile shows (0..1 over all threads; the
 * charted series comes from RRD instead).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NodeStatusInfo(CpuInfo cpuinfo, MemoryInfo memory, Double cpu) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CpuInfo(int cpus, int cores, int sockets, String model) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MemoryInfo(long total, long used, long free, long available) {
    }
}
