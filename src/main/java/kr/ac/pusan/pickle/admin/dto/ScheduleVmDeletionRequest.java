package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Contract op {@code scheduleVmDeletion} body. The minimum-notice rule on
 * {@code scheduledFor} is checked in the service against
 * {@code settings.admin_delete_min_notice_days}.
 */
public record ScheduleVmDeletionRequest(
        @NotNull Instant scheduledFor,
        @NotBlank @Size(max = 2000) String reason) {
}
