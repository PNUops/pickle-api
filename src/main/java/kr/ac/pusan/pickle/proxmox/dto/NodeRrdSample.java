package kr.ac.pusan.pickle.proxmox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of {@code GET /nodes/{n}/rrddata}. Metrics boxed for the same
 * gap-row reason as {@link VmRrdSample}. {@code rootused}/{@code roottotal}
 * cover the node root filesystem only — guest disks live in the thin pool,
 * which this series does not see (that number comes from the storage status
 * endpoint instead).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NodeRrdSample(
        Long time,
        Double cpu,
        Double maxcpu,
        Double iowait,
        Double loadavg,
        Double memtotal,
        Double memused,
        Double memavailable,
        Double swaptotal,
        Double swapused,
        Double roottotal,
        Double rootused,
        Double netin,
        Double netout) {
}
