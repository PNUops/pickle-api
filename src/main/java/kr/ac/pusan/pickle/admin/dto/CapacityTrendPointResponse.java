package kr.ac.pusan.pickle.admin.dto;

import java.time.LocalDate;

/** Contract schema {@code CapacityTrendPoint}. */
public record CapacityTrendPointResponse(
        LocalDate day,
        long vmCount,
        long vcpu,
        long memoryMb,
        long diskGb) {
}
