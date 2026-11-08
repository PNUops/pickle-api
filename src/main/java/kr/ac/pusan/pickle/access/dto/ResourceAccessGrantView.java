package kr.ac.pusan.pickle.access.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.access.AccessGranteeType;
import kr.ac.pusan.pickle.access.ResourceAccessGrant;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.user.User;
import org.jspecify.annotations.Nullable;

/** One entry in a VM's access list. */
@Schema(description = "리소스 접근 권한 한 건")
public record ResourceAccessGrantView(
        UUID id,
        @Schema(description = "USER는 지정한 사용자, WORKSPACE는 소유 워크스페이스 전체예요.")
        AccessGranteeType granteeType,
        @Schema(description = "대상 사용자예요. 워크스페이스 전체 항목이면 null이에요.")
        @Nullable Grantee user,
        @Schema(description = "이 대상이 리소스에서 갖는 등급")
        ResourceRole role,
        Instant createdAt) {

    @Schema(description = "접근 권한을 가진 사용자")
    public record Grantee(UUID userId, String name, String email) {
    }

    public static ResourceAccessGrantView of(ResourceAccessGrant grant, @Nullable User user) {
        Grantee grantee = user == null ? null
                : new Grantee(user.getPublicId(), user.getName(), user.getEmail());
        return new ResourceAccessGrantView(grant.getPublicId(), grant.getGranteeType(), grantee,
                grant.getRole(), grant.getCreatedAt());
    }
}
