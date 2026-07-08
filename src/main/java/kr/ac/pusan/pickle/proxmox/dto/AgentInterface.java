package kr.ac.pusan.pickle.proxmox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One guest NIC from {@code GET /nodes/{n}/qemu/{id}/agent/network-get-interfaces}
 * (the provision pipeline's step-8 verification checks the expected IP shows
 * up here). PVE uses kebab-case field names in agent responses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentInterface(
        String name,
        @JsonProperty("ip-addresses") List<IpAddress> ipAddresses) {

    public AgentInterface {
        ipAddresses = ipAddresses != null ? List.copyOf(ipAddresses) : List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IpAddress(
            @JsonProperty("ip-address") String ipAddress,
            @JsonProperty("ip-address-type") String type,
            int prefix) {
    }
}
