package kr.ac.pusan.pickle.publishing.dto;

import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code UpdatePublicationRequest} — documentation shape for
 * {@code PATCH /vms/{vmId}/publication}. The controller deliberately binds the
 * raw JSON instead of this record: the body distinguishes an omitted
 * {@code customDomain} (keep current) from an explicit {@code null} (detach),
 * which record binding cannot express. Keep this shape in sync with the
 * controller's field handling.
 */
public record UpdatePublicationRequest(
        @Nullable Integer port,
        @Nullable String customDomain) {
}
