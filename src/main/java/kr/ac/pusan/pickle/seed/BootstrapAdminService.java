package kr.ac.pusan.pickle.seed;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import kr.ac.pusan.pickle.auth.PasswordPolicy;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserPosition;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.PersonalWorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates the first system administrator without adding deployment inventory. */
@Service
public class BootstrapAdminService {

    static final int MIN_PASSWORD_LENGTH = 12;
    private static final long ISOLATED_BOOTSTRAP_LOCK = 0x50_4B_49_42L;
    private static final Set<String> FORBIDDEN_PASSWORDS = Set.of(
            "changeme", "change-me", "password", "passw0rd", "admin", "administrator",
            "secret", "pickle", "pickle-sysadmin-dev!", "pickle-orgadmin-dev!",
            "12345678", "123456789", "qwerty", "letmein");
    private static final Map<String, Long> ISOLATED_RESULT = Map.of(
            "users", 1L, "workspaces", 1L, "workspace_members", 1L);

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PersonalWorkspaceService personalWorkspaceService;
    private final PasswordPolicy passwordPolicy;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    public BootstrapAdminService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            PersonalWorkspaceService personalWorkspaceService, PasswordPolicy passwordPolicy,
            JdbcTemplate jdbcTemplate, EntityManager entityManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.personalWorkspaceService = personalWorkspaceService;
        this.passwordPolicy = passwordPolicy;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
    }

    /** Existing staging/prod semantics: validate every boot and create once. */
    @Transactional
    public void bootstrapOperational(String emailValue, String password) {
        String email = validate(emailValue, password);
        if (!userRepository.findByRole(UserRole.SYS_ADMIN).isEmpty()) {
            log.info("SYS_ADMIN 계정이 이미 존재하여 부트스트랩 시딩을 건너뜁니다.");
            return;
        }
        create(email, password, true);
    }

    /** One-shot isolated bootstrap: lock, prove empty, create, and prove the exact result. */
    @Transactional
    public void bootstrapIsolated(String emailValue, String password) {
        String email = validate(emailValue, password);
        jdbcTemplate.execute("select pg_advisory_xact_lock(" + ISOLATED_BOOTSTRAP_LOCK + ")");
        Map<String, Long> before = nonEmptyApplicationTables();
        if (!before.isEmpty()) {
            throw new IllegalStateException(
                    "격리 부트스트랩 대상 DB에 기존 애플리케이션 데이터가 있습니다: " + before.keySet());
        }
        create(email, password, false);
        entityManager.flush();
        Map<String, Long> after = nonEmptyApplicationTables();
        if (!after.equals(ISOLATED_RESULT)) {
            throw new IllegalStateException(
                    "격리 부트스트랩이 관리자 소유 행 외 데이터를 만들었습니다: " + after);
        }
    }

    private void create(String email, String password, boolean logEmail) {
        if (userRepository.existsByEmail(email)) {
            throw new IllegalStateException(
                    ProdBootstrapSeeder.EMAIL_ENV + " 이메일이 이미 다른 계정에 사용 중입니다. 다른 이메일을 지정하세요.");
        }
        if (logEmail) {
            log.info("초기 SYS_ADMIN 계정을 부트스트랩합니다: {}", email);
        } else {
            log.info("격리 검증용 초기 SYS_ADMIN 계정을 부트스트랩합니다.");
        }
        User admin = new User(email, passwordEncoder.encode(password), "시스템 관리자");
        admin.setRole(UserRole.SYS_ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setEmailVerifiedAt(Instant.now());
        admin.setProfile(UserPosition.STAFF, null, null, "플랫폼 운영");
        admin = userRepository.save(admin);
        personalWorkspaceService.ensurePersonalWorkspace(admin);
    }

    private String validate(String emailValue, String password) {
        String email = emailValue == null ? null : emailValue.trim();
        if (email == null || email.isBlank()) {
            throw new IllegalStateException(ProdBootstrapSeeder.EMAIL_ENV
                    + " 환경 변수가 비어 있습니다. 초기 관리자 이메일을 설정한 뒤 다시 시작하세요.");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(ProdBootstrapSeeder.PASSWORD_ENV
                    + " 환경 변수가 비어 있습니다. 초기 관리자 비밀번호를 설정한 뒤 다시 시작하세요.");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(ProdBootstrapSeeder.PASSWORD_ENV + " 비밀번호가 너무 짧습니다("
                    + MIN_PASSWORD_LENGTH + "자 이상 필요). 더 강력한 비밀번호로 다시 시작하세요.");
        }
        if (FORBIDDEN_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(ProdBootstrapSeeder.PASSWORD_ENV
                    + " 비밀번호가 추측 가능한 기본값입니다. 실제 비밀번호로 교체한 뒤 다시 시작하세요.");
        }
        try {
            passwordPolicy.validate(password);
        } catch (RuntimeException weak) {
            throw new IllegalStateException(ProdBootstrapSeeder.PASSWORD_ENV
                    + " 비밀번호가 보안 정책을 통과하지 못했습니다. 더 강력한 비밀번호로 다시 시작하세요.", weak);
        }
        return email;
    }

    private Map<String, Long> nonEmptyApplicationTables() {
        Boolean upstreamsExact = jdbcTemplate.queryForObject("""
                select count(*) = 3 and count(*) filter (where
                    (ref='openai' and kind='EXTERNAL_API' and display_name='OpenAI'
                        and org_id is null and not dedicated and enabled and not passthrough
                        and note='자체 서빙이 서기 전까지 pnu- 모델을 임시로 받치는 외부 업스트림')
                    or (ref='openrouter' and kind='EXTERNAL_API' and display_name='OpenRouter'
                        and org_id is null and not dedicated and enabled and passthrough
                        and note='상용 모델 경로. 키별 자격증명으로 호출되며 금액 한도는 OpenRouter가 강제한다')
                    or (ref='dgx' and kind='ON_PREM' and display_name='DGX Spark'
                        and org_id is null and not dedicated and not enabled and not passthrough
                        and note='자체 서빙 하드웨어 자리. vLLM 서빙이 서면 활성화한다')) = 3
                  from llm_upstreams
                """, Boolean.class);
        if (!Boolean.TRUE.equals(upstreamsExact)) {
            throw new IllegalStateException(
                    "격리 부트스트랩의 schema-defined LLM upstream 행이 예상과 다릅니다.");
        }
        List<String> tables = jdbcTemplate.queryForList(
                "select tablename from pg_tables where schemaname = current_schema()"
                        + " and tablename <> 'flyway_schema_history'"
                        + " and tablename <> 'llm_upstreams'"
                        + " and tablename not like 'jobrunr\\_%' escape '\\' order by tablename",
                String.class);
        Map<String, Long> nonEmpty = new LinkedHashMap<>();
        for (String table : tables) {
            String quoted = '"' + table.replace("\"", "\"\"") + '"';
            Long count = jdbcTemplate.queryForObject("select count(*) from " + quoted, Long.class);
            if (count != null && count > 0) {
                nonEmpty.put(table, count);
            }
        }
        return nonEmpty;
    }
}
