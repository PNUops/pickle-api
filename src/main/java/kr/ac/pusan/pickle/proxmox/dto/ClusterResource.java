package kr.ac.pusan.pickle.proxmox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One entry of {@code GET /cluster/resources} (fields we use for placement
 * and reconciliation). {@code vmid}/{@code maxcpu}/{@code tags} are nullable:
 * storage/node entries carry no vmid, and untagged guests omit {@code tags}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClusterResource(
        Integer vmid,
        String name,
        String status,
        String type,
        String node,
        Long maxmem,
        Integer maxcpu,
        Long maxdisk,
        String tags) {
}
