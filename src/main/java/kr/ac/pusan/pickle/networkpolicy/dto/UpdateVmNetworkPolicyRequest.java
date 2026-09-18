package kr.ac.pusan.pickle.networkpolicy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import kr.ac.pusan.pickle.networkpolicy.VmNetworkRule;
import org.jspecify.annotations.Nullable;

/** Complete ordered replacement with optimistic revision checking. */
public record UpdateVmNetworkPolicyRequest(
        @NotNull @Min(0) Long expectedRevision,
        @NotNull @Size(max = 128) List<@Valid Rule> rules) {

    public record Rule(
            @NotNull VmNetworkRule.Direction direction,
            @NotNull VmNetworkRule.Action action,
            @NotNull VmNetworkRule.Protocol protocol,
            @NotBlank @Size(max = 64) String peer,
            @Nullable @Min(1) @Max(65535) Integer portStart,
            @Nullable @Min(1) @Max(65535) Integer portEnd) {
    }
}
