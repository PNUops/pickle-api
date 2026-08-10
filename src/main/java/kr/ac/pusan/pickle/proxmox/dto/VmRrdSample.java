package kr.ac.pusan.pickle.proxmox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of {@code GET /nodes/{n}/qemu/{id}/rrddata}. Every metric is boxed:
 * RRD rows omit the keys for intervals when the VM was not running (a stopped
 * VM's rows carry only time/maxima), and those nulls are the honest "no data"
 * a chart renders as a gap. {@code mem} is guest-internal usage when the guest
 * agent reports it (our images ship the agent); {@code memhost} is the
 * hypervisor-side view including guest page cache. The qemu {@code disk}
 * series is always 0 and deliberately unmapped.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VmRrdSample(
        Long time,
        Double cpu,
        Double maxcpu,
        Double mem,
        Double memhost,
        Double maxmem,
        Double netin,
        Double netout,
        Double diskread,
        Double diskwrite) {
}
