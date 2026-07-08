package kr.ac.pusan.pickle.audit;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes append-only {@code audit_logs} rows. Runs in its own transaction so
 * audit entries survive business-transaction rollbacks (e.g. failed logins).
 * Detail maps are whitelisted field-by-field at call sites — never secrets
 * (docs/plan/07).
 */
@Service
public class AuditService {

    public static final String AUTH_SIGNUP = "auth.signup";
    public static final String AUTH_VERIFY = "auth.verify";
    public static final String AUTH_LOGIN = "auth.login";
    public static final String AUTH_LOGIN_FAILED = "auth.login_failed";
    public static final String AUTH_REFRESH_REUSE_DETECTED = "auth.refresh_reuse_detected";
    public static final String AUTH_LOGOUT = "auth.logout";
    public static final String GROUP_CREATE = "group.create";
    public static final String GROUP_MEMBER_ADD = "group.member_add";
    public static final String GROUP_MEMBER_UPDATE = "group.member_update";
    public static final String GROUP_MEMBER_REMOVE = "group.member_remove";
    public static final String REQUEST_CREATE = "request.create";
    public static final String REQUEST_CANCEL = "request.cancel";
    public static final String REQUEST_APPROVE = "request.approve";
    public static final String REQUEST_REJECT = "request.reject";
    public static final String ORG_CREATE = "org.create";
    public static final String ORG_UPDATE = "org.update";
    public static final String USER_ROLE_UPDATE = "user.role_update";
    public static final String VM_EMERGENCY_DELETE = "vm.emergency_delete";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long actorId, String actorRole, String action, String targetType, Long targetId,
            Map<String, Object> detail, String ip) {
        String detailJson = (detail == null || detail.isEmpty()) ? null : objectMapper.writeValueAsString(detail);
        jdbcTemplate.update("""
                insert into audit_logs (actor_id, actor_role, action, target_type, target_id, detail, ip)
                values (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, actorId, actorRole, action, targetType, targetId, detailJson, ip);
    }
}
