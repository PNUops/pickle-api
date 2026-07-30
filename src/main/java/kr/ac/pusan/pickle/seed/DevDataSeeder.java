package kr.ac.pusan.pickle.seed;

import java.time.Instant;
import kr.ac.pusan.pickle.consent.TermsService;
import kr.ac.pusan.pickle.group.PersonalGroupService;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import kr.ac.pusan.pickle.user.User;
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

    public static final String ORG_NAME = "테스트 기관";
    public static final String ORG_SLUG = "test-org";

    private final UserRepository userRepository;
    private final OrgRepository orgRepository;
    private final PersonalGroupService personalGroupService;
    private final PasswordEncoder passwordEncoder;
    private final SeedProperties properties;
    private final TermsService termsService;
    private final JdbcTemplate jdbcTemplate;

    public DevDataSeeder(UserRepository userRepository, OrgRepository orgRepository,
            PersonalGroupService personalGroupService, PasswordEncoder passwordEncoder,
            SeedProperties properties, TermsService termsService, JdbcTemplate jdbcTemplate) {
        this.userRepository = userRepository;
        this.orgRepository = orgRepository;
        this.personalGroupService = personalGroupService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.termsService = termsService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedUser(properties.sysadminEmail(), properties.sysadminPassword(), "시스템 관리자",
                UserRole.SYS_ADMIN, null);

        Org org = orgRepository.findBySlug(ORG_SLUG)
                .orElseGet(() -> {
                    log.info("Seeding org '{}' ({})", ORG_NAME, ORG_SLUG);
                    Org seedOrg = new Org(ORG_NAME, ORG_SLUG, ORG_NAME + " (개발용 시드 기관)");
                    seedOrg.setHidden(true);
                    return orgRepository.save(seedOrg);
                });

        seedUser(properties.orgadminEmail(), properties.orgadminPassword(), "기관 관리자",
                UserRole.ORG_ADMIN, org.getId());

        enableGatewayKillSwitches();
        seedInventory();
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
                        + " 'ubuntu', 1000, ?, 1, 10, 'ACTIVE'::template_status,"
                        + " '개발용 시드 이미지')", nodeId);
        log.info("Empty inventory: seeded the development OS image (ubuntu-24.04)");
    }

    /**
     * Seeds the relay with a public address, unlike the migration this replaces.
     * A relay row without one hands out forwarded ports that resolve to nothing,
     * and no setter or endpoint exists to fill the column afterwards. The name is the
     * one the migration used, because tests look the seeded relay up by it.
     */
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
     * <p>The migrations seed both to {@code false} on purpose: in production a
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
            user = userRepository.save(user);
            personalGroupService.ensurePersonalGroup(user);
        });
        // Seed accounts bypass signup, so grant consent to the current documents
        // idempotently (a fresh DB seeds users after the V42 backfill ran empty).
        userRepository.findByEmail(email)
                .ifPresent(user -> termsService.ensureCurrentConsents(user.getId()));
    }
}
