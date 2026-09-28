package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Contract op {@code previewAdminBulkChange} response: one item per requested target, in request order. */
public record AdminBulkChangePreviewResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AdminBulkChangePreviewItem> items) {
}
