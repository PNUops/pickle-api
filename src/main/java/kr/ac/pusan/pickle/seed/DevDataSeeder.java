package kr.ac.pusan.pickle.seed;

import java.time.Instant;
import kr.ac.pusan.pickle.consent.TermsService;
import kr.ac.pusan.pickle.workspace.PersonalWorkspaceService;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import kr.ac.pusan.pickle.profile.DepartmentCatalog;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserPosition;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent dev/test seed (insert-if-absent by email/slug): SYS_ADMIN, one
 * hidden test org (테스트 기관/test-org) and its ORG_ADMIN. The org is a
 * test/smoke fixture (an ORG_ADMIN cannot exist without an org), so it is
 * seeded hidden and never shows up in USER-role org listings. Runs at startup instead of a
 * migration so no password hash lands in git. Seed accounts
 * are pre-verified (they bypass the @pusan.ac.kr self-signup restriction).
 *
 * <p>The configured PICKLE_SEED_* password is the source of truth: if an
 * existing seed account's hash no longer matches it, the hash is re-encoded at
 * startup, so rotating the env value rotates the account. There is no built-in
 * default (it would be public in git), so a blank value fails startup — set
 * PICKLE_SEED_*_PASSWORD in /etc/pickle/api.env.
 */
@Component
@Profile({"dev", "test"})
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    /** Template the development catalog row points at. Tests stub the clone path for
     * this id, so it lives here rather than in three places that can drift apart. */
    public static final int SEED_TEMPLATE_VMID = 1001;

    public static final String ORG_NAME = "테스트 기관";

    private final UserRepository userRepository;
    private final OrgRepository orgRepository;
    private final PersonalWorkspaceService personalWorkspaceService;
    private final PasswordEncoder passwordEncoder;
    private final SeedProperties properties;
    private final TermsService termsService;
    private final JdbcTemplate jdbcTemplate;

    public DevDataSeeder(UserRepository userRepository, OrgRepository orgRepository,
            PersonalWorkspaceService personalWorkspaceService, PasswordEncoder passwordEncoder,
            SeedProperties properties, TermsService termsService, JdbcTemplate jdbcTemplate) {
        this.userRepository = userRepository;
        this.orgRepository = orgRepository;
        this.personalWorkspaceService = personalWorkspaceService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.termsService = termsService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Both run before the accounts: the settings rows carry the switches the
        // rest of startup reads, and seedUser grants consent to whatever terms
        // documents exist at that moment, so an empty table there would leave the
        // seed accounts permanently behind the consent gate.
        seedSettings();
        seedTerms();

        seedUser(properties.sysadminEmail(), properties.sysadminPassword(), "시스템 관리자",
                UserRole.SYS_ADMIN, null);

        Org org = orgRepository.findFirstByNameOrderByIdAsc(ORG_NAME)
                .orElseGet(() -> {
                    log.info("Seeding org '{}'", ORG_NAME);
                    Org seedOrg = new Org(ORG_NAME, ORG_NAME + " (개발용 시드 기관)");
                    seedOrg.setHidden(true);
                    return orgRepository.save(seedOrg);
                });

        seedUser(properties.orgadminEmail(), properties.orgadminPassword(), "기관 관리자",
                UserRole.ORG_ADMIN, org.getId());

        enableGatewayKillSwitches();
        seedInventory();
    }

    /**
     * Fills the runtime-tunable {@code settings} store for a development or test
     * database.
     *
     * <p>The migrations seed none of these keys. A root domain names a zone this
     * deployment owns, the two approval thresholds are judgements about real
     * hardware, the rate limits are judgements about a real relay, and the
     * contact address is a real mailbox: all of them are content a deployment
     * supplies, not schema. An operator bootstrap script writes them in
     * production. Development and test run no such script, and while every reader
     * falls back cleanly on a missing row (see the typed getters), the fallbacks
     * are conservative by design, so an unseeded database would quietly answer
     * "no root domains" and "no reserved names" to tests that are about
     * something else.</p>
     *
     * <p>Values follow the deployed ones with two deliberate departures. Both
     * gateway kill switches start {@code false}, because a development database
     * must not assume an SSH gateway or a terminal bridge exists (the fresh-database
     * pass below turns them on where that is safe). And the reserved and profanity
     * lists are the short original sets rather than the curated production lists:
     * a test that asserted on a few hundred operator-chosen names would be
     * asserting on an operations decision.</p>
     *
     * <p>Acts only when the table is empty, so an operator or a test that has
     * already arranged a value is never overwritten.</p>
     */
    private void seedSettings() {
        Integer rows = jdbcTemplate.queryForObject("select count(*) from settings", Integer.class);
        if (rows == null || rows > 0) {
            return;
        }
        setting("allowed_root_domains", "[\"pusan.dev\"]",
                "VM 신청에서 선택할 수 있는 루트 도메인 목록.");
        setting("reserved_subdomains",
                "[\"www\",\"api\",\"admin\",\"ssh\",\"mail\",\"console\",\"staging\"]",
                "신청할 수 없는 예약 서브도메인 목록.");
        setting("profanity_subdomains",
                "[\"fuck\",\"shit\",\"porn\",\"sex\",\"admin-official\",\"pnu-official\"]",
                "서브도메인 금칙어(욕설·사칭) 목록. 관리자가 확장할 수 있습니다.");
        setting("vcpu_overcommit_warn", "3.0",
                "승인 화면 경고 임계값 — 할당 vCPU / 물리 스레드 비율.");
        setting("memory_usage_warn", "0.8",
                "승인 화면 경고 임계값 — 할당 메모리 / 물리 메모리 비율.");
        setting("ip_quarantine_hours", "24",
                "회수된 IP를 재할당하지 않고 격리하는 시간(시간). 릴레이 에이전트가 보관된 스냅샷을"
                        + " 재적용할 수 있는 기간(24시간)보다 짧게 설정할 수 없습니다.");
        setting("vm_delete_grace_hours", "168",
                "본인 삭제 접수 후 파기까지의 유예 시간(시간). 유예는 관리자 복구용 안전망.");
        setting("ssh_gateway_enabled", "false",
                "SSH 게이트웨이 전체 활성화 (킬 스위치). false면 모든 SSH 접속이 차단됩니다.");
        setting("web_terminal_enabled", "false",
                "웹 터미널(브라우저 xterm.js) 전역 킬 스위치. false면 티켓 발급·재교환이 모두 거부되고,"
                        + " 진행 중이던 세션은 다음 60초 재검증에서 종료됩니다."
                        + " 우선순위: 킬 스위치 > per-VM 차단 > 멤버십.");
        setting("notification_retention_days", "365",
                "알림 보관 기간(일). 기간이 지난 알림은 정리 작업이 삭제합니다. (30~3650)");
        setting("vm_expiry_notice_days", "[14,7,1]",
                "VM 사용 종료 사전 알림 시점(D-일). 내림차순 단계 최대 5개, 각 1~90."
                        + " D-1 알림은 HIGH 중요도로 표시됩니다.");
        setting("vm_expiry_autostop_enabled", "true",
                "사용 기간(end_date, 포함)이 지난 VM을 매시간 자동 정지할지 여부."
                        + " 끄면 예고 알림만 발송됩니다.");
        setting("maintenance_mode", "false",
                "점검 모드. true면 관리자 계층이 아닌 모든 인증 요청이 503(MAINTENANCE_MODE)으로"
                        + " 거부됩니다. 변경은 15초 이내 반영.");
        setting("maintenance_message", "\"\"",
                "점검 모드 안내 문구. 비우면 기본 안내 문구를 사용합니다.");
        setting("banner_message", "\"\"",
                "전역 공지 배너 문구(점검 모드와 독립 — 콘솔 상단 배너). 비우면 배너를 표시하지 않습니다.");
        setting("contact_email", "\"\"",
                "운영 문의 이메일(콘솔 푸터·점검·오류 화면에 표시). 비우면 표시하지 않습니다.");
        setting("port_forwarding_enabled", "false",
                "포트 포워딩(릴레이 공개 포트) 기능 스위치. false면 신규 생성이 차단됩니다"
                        + " (기존 매핑은 유지).");
        setting("port_forward_alloc_limit_per_hour", "20",
                "사용자별 포트 포워딩 생성 허용 횟수(시간당).");
        setting("port_forward_band_alert_percent", "80",
                "릴레이 공개 포트 대역 사용률 경고 임계값(%). 도달 시 시스템 관리자에게 알림을 보냅니다.");
        setting("port_forward_suspend_conns_per_min", "6000",
                "매핑별 분당 신규 연결 수 자동 정지 임계값. 초과 시 해당 매핑을 자동 SUSPENDED 처리합니다.");
        setting("port_forward_suspend_mbytes_per_min", "1000",
                "매핑별 분당 전송량(MB) 자동 정지 임계값. 초과 시 해당 매핑을 자동 SUSPENDED 처리합니다.");
        log.info("Empty settings: seeded the development settings");
    }

    private void setting(String key, String valueJson, String description) {
        jdbcTemplate.update("insert into settings (key, value, description)"
                + " values (?, ?::jsonb, ?)", key, valueJson, description);
    }

    /**
     * The terms and privacy documents a development or test database needs for
     * the consent gate to have something to enforce.
     *
     * <p>The migrations publish neither. Legal text binds one operator to one set
     * of users, is revised on its own schedule, and a revision is a row rather
     * than a migration, so an operator bootstrap script publishes the real
     * documents. Without a row here signup fails closed with
     * "약관 문서가 준비되지 않았습니다" and every account-creating test fails with
     * it, so the two documents are seeded — with a placeholder body. The real
     * prose is not copied in: it would put a legal document under test fixtures,
     * where an edit made for a test's convenience is an edit to the terms users
     * agreed to. No test asserts on its wording.</p>
     */
    private void seedTerms() {
        Integer documents = jdbcTemplate.queryForObject("select count(*) from terms_versions",
                Integer.class);
        if (documents == null || documents > 0) {
            return;
        }
        jdbcTemplate.update("insert into terms_versions (doc_type, version, title, body,"
                + " effective_at) values"
                + " ('TERMS_OF_SERVICE', 1, ?, ?, now()), ('PRIVACY_POLICY', 1, ?, ?, now())",
                "부산대학교 클라우드 플랫폼 서비스 이용약관",
                "# 개발용 이용약관 자리표시자 — 실제 약관은 운영자가 게시합니다.",
                "부산대학교 클라우드 플랫폼 개인정보처리방침",
                "# 개발용 개인정보처리방침 자리표시자 — 실제 문서는 운영자가 게시합니다.");
        log.info("Empty terms: seeded the development placeholder documents");
    }

    /**
     * Gives a development or test database the inventory it needs to be usable:
     * a node, an IP pool attached to it, one OS image, a relay and the platform
     * wildcard certificate.
     *
     * <p>The migrations deliberately seed none of this. Every one of these rows
     * describes the machine and network the platform is deployed on, so a row
     * written by a migration is wrong rather than unhelpful anywhere else: the
     * node's memory is a hard filter in placement, the certificate row is read as
     * proof that TLS is covered, and the relay's public address is what a user
     * connects to. In production an operations script reads the real host and
     * writes them. Development and test have no such step, and without these rows
     * nothing can be provisioned at all.</p>
     *
     * <p>Each part acts only when its table is empty, so an operator or a test that
     * has already arranged something is never overwritten. The values are the ones
     * the migrations used to carry, kept identical so tests that look the seeded
     * node or pool up by name keep finding them.</p>
     */
    private void seedInventory() {
        Long nodeId = seedNode();
        if (nodeId == null) {
            return;
        }
        seedIpPool(nodeId);
        seedOsImage(nodeId);
        seedVmFlavors();
        seedRelay();
        seedPlatformCertificate();
    }

    /** Returns the node every other part attaches to, seeding it when there is none. */
    private Long seedNode() {
        Long existing = jdbcTemplate.query("select id from nodes order by id limit 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (existing != null) {
            return existing;
        }
        jdbcTemplate.update(
                "insert into nodes (name, api_host, cpu_threads, memory_mb, vm_bridge, storage)"
                        + " values ('pve1', 'https://pve1:8006', 40, 79872, 'vmbr2', 'local-lvm')");
        log.info("Empty inventory: seeded the development node (pve1)");
        return jdbcTemplate.queryForObject("select id from nodes where name = 'pve1'", Long.class);
    }

    private void seedIpPool(long nodeId) {
        Integer pools = jdbcTemplate.queryForObject("select count(*) from ip_pools", Integer.class);
        if (pools == null || pools > 0) {
            return;
        }
        jdbcTemplate.update(
                "insert into ip_pools (name, cidr, gateway, dns, reserved_ranges) values"
                        + " ('guest-private', '172.29.0.0/16', '172.29.0.1', '[\"8.8.8.8\"]'::jsonb,"
                        + " '[{\"from\": \"172.29.0.0\", \"to\": \"172.29.0.255\"},"
                        + " {\"from\": \"172.29.255.0\", \"to\": \"172.29.255.255\"}]'::jsonb)");
        jdbcTemplate.update("update nodes set ip_pool_id = (select id from ip_pools"
                + " where name = 'guest-private'), updated_at = now() where id = ?", nodeId);
        log.info("Empty inventory: seeded the development IP pool (guest-private)");
    }

    private void seedOsImage(long nodeId) {
        Integer images = jdbcTemplate.queryForObject("select count(*) from os_images", Integer.class);
        if (images == null || images > 0) {
            return;
        }
        jdbcTemplate.update(
                "insert into os_images (name, display_name, os_family, os_version,"
                        + " ssh_username, proxmox_vmid, node_id, version, min_disk_gb, status, notes)"
                        + " values ('ubuntu-24.04', 'Ubuntu 24.04 LTS', 'ubuntu', '24.04',"
                        + " 'ubuntu', " + SEED_TEMPLATE_VMID + ", ?, 1, 10, 'ACTIVE'::catalog_status,"
                        + " '개발용 시드 이미지')", nodeId);
        log.info("Empty inventory: seeded the development OS image (ubuntu-24.04)");
    }

    /**
     * Seeds the relay with a public address, unlike the migration this replaces.
     * A relay row without one hands out forwarded ports that resolve to nothing,
     * and no setter or endpoint exists to fill the column afterwards. The name is the
     * one the migration used, because tests look the seeded relay up by it.
     */
    /**
     * Spec presets for a development or test database.
     *
     * <p>The migrations seed none: which sizes a deployment offers is a policy the
     * operator sets against real hardware, and the admin console can create and edit
     * them, so production has a write path and needs no seed. Development and test do
     * not go through that console, and several test classes resolve a preset named
     * {@code basic}, so an empty table there is a suite that cannot run.</p>
     */
    private void seedVmFlavors() {
        Integer flavors = jdbcTemplate.queryForObject("select count(*) from vm_flavors", Integer.class);
        if (flavors == null || flavors > 0) {
            return;
        }
        jdbcTemplate.update("insert into vm_flavors (name, display_name, vcpu, memory_mb,"
                + " disk_gb, notes) values"
                + " ('small', '소형', 1, 1024, 10, '봇, 크론 작업 등 초소형 서비스에 적합합니다.'),"
                + " ('basic', '기본형', 2, 2048, 20, '대부분의 수업·동아리 프로젝트에 적합합니다.'),"
                + " ('large', '대형', 4, 8192, 40, '대형 스펙은 신청 시 사용 사유를 반드시 적어 주세요.')");
        log.info("Empty inventory: seeded the development spec presets");
    }

    private void seedRelay() {
        Integer relays = jdbcTemplate.queryForObject("select count(*) from relays", Integer.class);
        if (relays == null || relays > 0) {
            return;
        }
        jdbcTemplate.update("insert into relays (name, source_ip, public_host,"
                + " port_band_start, port_band_end) values ('lightsail-1', '10.100.100.1',"
                + " 'relay.invalid', 10000, 19999)");
        log.info("Empty inventory: seeded the development relay (lightsail-1)");
    }

    /**
     * The platform wildcard the publishing path resolves for non-custom domains.
     * Expiry is a year out rather than a decade: on a development database a stale
     * date should surface in the admin certificate list, not sit quiet until 2040.
     */
    private void seedPlatformCertificate() {
        Integer certs = jdbcTemplate.queryForObject(
                "select count(*) from certificates where kind = 'ORIGIN_CA_WILDCARD'", Integer.class);
        if (certs == null || certs > 0) {
            return;
        }
        String root = jdbcTemplate.query(
                "select value->>0 from settings where key = 'allowed_root_domains'",
                rs -> rs.next() ? rs.getString(1) : null);
        if (root == null || root.isBlank()) {
            log.warn("No allowed root domain, so the development wildcard certificate stays absent");
            return;
        }
        jdbcTemplate.update("insert into certificates (domain_id, kind, scope, not_after, status)"
                + " values (null, 'ORIGIN_CA_WILDCARD', ?, now() + interval '1 year', 'ACTIVE')",
                "*." + root);
        log.info("Empty inventory: seeded the development wildcard certificate for *.{}", root);
    }

    /**
     * Turns the SSH-gateway and web-terminal kill switches on when the database is
     * freshly created.
     *
     * <p>Both are seeded {@code false} above on purpose: in production a
     * capability is switched on deliberately, after its infrastructure is wired and
     * verified. On a development database that default is a trap — recreating the
     * database silently leaves user SSH and the web terminal dead while the code
     * looks fine, and the last person to hit it has no reason to suspect a settings
     * row.</p>
     *
     * <p>The guard is an <em>empty audit log</em>, not the switch value: a {@code false}
     * an operator set deliberately is byte-identical to the migration default, so the
     * value alone cannot tell the two apart. Any use of the system — including
     * disabling a switch through the admin API, which is audited — leaves audit rows,
     * so this runs on a fresh database and never undoes an incident-response decision
     * on a restart.</p>
     */
    private void enableGatewayKillSwitches() {
        Integer auditRows = jdbcTemplate.queryForObject("select count(*) from audit_logs", Integer.class);
        if (auditRows == null || auditRows > 0) {
            return;
        }
        int flipped = jdbcTemplate.update(
                "update settings set value = 'true'::jsonb, updated_at = now()"
                        + " where key in ('ssh_gateway_enabled', 'web_terminal_enabled')"
                        + " and value = 'false'::jsonb");
        if (flipped > 0) {
            log.info("Fresh database: enabled {} gateway kill switch(es) for development", flipped);
        }
    }

    private void seedUser(String email, String password, String name, UserRole role, Long orgId) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Seed password for " + email + " is not set. Provide "
                    + "PICKLE_SEED_SYSADMIN_PASSWORD / PICKLE_SEED_ORGADMIN_PASSWORD via "
                    + "/etc/pickle/api.env (there is no default: it would be public in git).");
        }
        userRepository.findByEmail(email).ifPresentOrElse(existing -> {
            if (!passwordEncoder.matches(password, existing.getPasswordHash())) {
                log.info("Seed account {} hash differs from configured password — re-encoding "
                        + "(PICKLE_SEED_* env is the source of truth)", email);
                existing.setPasswordHash(passwordEncoder.encode(password));
                userRepository.save(existing);
            }
        }, () -> {
            log.info("Seeding {} account {}", role, email);
            User user = new User(email, passwordEncoder.encode(password), name);
            user.setRole(role);
            user.setOrgId(orgId);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            // Filled in so the profile prompt does not greet every dev login:
            // a prompt that is always on screen cannot be verified.
            user.setProfile(UserPosition.STAFF, null, DepartmentCatalog.OTHER);
            user = userRepository.save(user);
            personalWorkspaceService.ensurePersonalWorkspace(user);
        });
        // Seed accounts bypass signup, so grant consent to the current documents
        // idempotently: no migration grants consent, so a seeded account starts with
        // none whatever order the seeder runs in.
        userRepository.findByEmail(email)
                .ifPresent(user -> termsService.ensureCurrentConsents(user.getId()));
    }
}
