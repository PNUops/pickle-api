package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Contract op {@code scheduleVmDeletion} body. {@code scheduledFor} only has
 * to be a future instant — the minimum-notice floor was dropped (2026-07-27);
 * the console warns below the recommended notice window instead.
 */
public record ScheduleVmDeletionRequest(
        @NotNull Instant scheduledFor,
        @NotBlank @Size(max = 2000) String reason) {
}
