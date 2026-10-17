package kr.ac.pusan.pickle.workspace;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Re-reads authority after the administration lock, independently of the HTTP principal. */
@Component
public class LifecycleActors {
    private final JdbcTemplate jdbc;
    private final kr.ac.pusan.pickle.config.MfaProperties mfaProperties;
    private final kr.ac.pusan.pickle.mfa.MfaService mfaService;
    public LifecycleActors(JdbcTemplate jdbc, kr.ac.pusan.pickle.config.MfaProperties mfaProperties,
            kr.ac.pusan.pickle.mfa.MfaService mfaService) {
        this.jdbc = jdbc; this.mfaProperties = mfaProperties; this.mfaService = mfaService;
    }
    public AuthenticatedUser current(AuthenticatedUser actor) {
        AuthenticatedUser current = jdbc.query("select public_id,email,role::text,status::text from users where id=?",
                rs -> {
                    if (!rs.next() || !"ACTIVE".equals(rs.getString("status"))) return null;
                    UserRole role = UserRole.valueOf(rs.getString("role"));
                    Map<Long, UserRole> roles = new HashMap<>();
                    if (role.isOrgTier()) jdbc.query("select org_id,role::text from user_org_roles where user_id=? order by org_id",
                            (org.springframework.jdbc.core.RowCallbackHandler) row -> roles.put(row.getLong(1), UserRole.valueOf(row.getString(2))), actor.id());
                    return new AuthenticatedUser(actor.id(), rs.getObject("public_id", UUID.class), rs.getString("email"), role, roles);
                }, actor.id());
        if (current == null || !current.publicId().equals(actor.publicId())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_TOKEN_INVALID,
                    "인증이 필요합니다", "현재 활성 계정으로 다시 로그인해 주세요.");
        }
        if (mfaProperties.enforceAdmin() && current.role().isSysTier() && !mfaService.isEnrolled(current.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.MFA_ENROLLMENT_REQUIRED,
                    "2단계 인증 등록이 필요합니다", "관리자 계정은 2단계 인증 등록 후 이용할 수 있습니다.");
        }
        return current;
    }
}
