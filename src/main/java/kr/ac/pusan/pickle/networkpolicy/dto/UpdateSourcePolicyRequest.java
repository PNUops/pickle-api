package kr.ac.pusan.pickle.networkpolicy.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Complete replacement of one public path's saved source allowlist. */
public record UpdateSourcePolicyRequest(
        @NotNull @Min(0) Long expectedRevision,
        @NotNull @Size(max = 128) List<@NotNull @Size(max = 64) String> allowedCidrs) {
}
