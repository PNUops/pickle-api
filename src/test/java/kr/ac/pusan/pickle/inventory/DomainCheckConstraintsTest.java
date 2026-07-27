package kr.ac.pusan.pickle.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V11 defense-in-depth CHECK constraints: direct JDBC
 * writes that bypass the service layer must be refused by the database. The
 * embedded-PG suite already proves the migration applies to the seeded data;
 * these cases prove the constraints actually fire.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class DomainCheckConstraintsTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long orgId;
    private long nodeId;
    private long templateId;
    private long requesterId;
    private long groupId;

    @BeforeEach
    void setUp() {
        orgId = SeedFixtures.seedOrgId(jdbc);
        nodeId = jdbc.queryForObject("select id from nodes where name = 'pve1'", Long.class);
        templateId = jdbc.queryForObject("select min(id) from vm_templates", Long.class);
        requesterId = SeedFixtures.orgadminId(jdbc);
        String slug = "chk-" + UUID.randomUUID().toString().substring(0, 8);
        groupId = jdbc.queryForObject(
                "insert into groups (kind, name, slug) values ('TEAM', ?, ?) returning id",
                Long.class, slug, slug);
    }

    @Test
    void studentWithOrgIdIsRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into users (email, password_hash, name, role, org_id, status)
                values (?, 'x', '학생', 'USER', ?, 'ACTIVE')
                """, UUID.randomUUID() + "@pusan.ac.kr", orgId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_users_org_role");
    }

    @Test
    void orgAdminWithoutOrgIdIsRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into users (email, password_hash, name, role, status)
                values (?, 'x', '기관관리자', 'ORG_ADMIN', 'ACTIVE')
                """, UUID.randomUUID() + "@pusan.ac.kr"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_users_org_role");
    }

    @Test
    void nonPositiveRequestSpecIsRejected() {
        assertThatThrownBy(() -> insertRequest(0, 1024, 10, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_vm_requests_positive_specs");
    }

    @Test
    void reversedRequestDatesAreRejected() {
        assertThatThrownBy(() -> insertRequest(1, 1024, 10, "2026-08-01", "2026-07-01"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_vm_requests_date_order");
    }

    @Test
    void nonPositiveVmSpecIsRejected() {
        long requestId = insertRequest(1, 1024, 10, null, null);
        String hostname = "chk-vm-" + UUID.randomUUID().toString().substring(0, 12);
        assertThatThrownBy(() -> jdbc.update("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb)
                values (?, ?, ?, ?, ?, ?, ?, 1, 0, 10)
                """, nodeId, groupId, orgId, requestId, hostname, hostname, templateId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_vms_positive_specs");
    }

    @Test
    void approveReviewMissingGrantedSpecIsRejected() {
        long requestId = insertRequest(1, 1024, 10, null, null);
        // APPROVE with the granted spec absent (granted_template_id null) must fail.
        assertThatThrownBy(() -> jdbc.update("""
                insert into vm_request_reviews (request_id, reviewer_id, decision,
                                                granted_vcpu, granted_memory_mb, granted_disk_gb)
                values (?, ?, 'APPROVE', 2, 2048, 20)
                """, requestId, requesterId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_reviews_approve_granted");
    }

    @Test
    void rejectReviewWithNullGrantsIsAccepted() {
        // The mirror case: REJECT rows legitimately leave every granted column
        // null and must pass the approve-granted constraint.
        long requestId = insertRequest(1, 1024, 10, null, null);
        int inserted = jdbc.update("""
                insert into vm_request_reviews (request_id, reviewer_id, decision, comment)
                values (?, ?, 'REJECT', '반려 사유')
                """, requestId, requesterId);
        assertThat(inserted).isEqualTo(1);
    }

    private long insertRequest(int vcpu, int memoryMb, int diskGb, String start, String end) {
        return jdbc.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb,
                                         req_start_date, req_end_date,
                                         need_ssh, need_http, need_public)
                values (?, ?, ?, '제약 테스트', ?, ?, ?, ?,
                        cast(? as date), cast(? as date), true, false, false)
                returning id
                """, Long.class, groupId, orgId, requesterId, templateId,
                vcpu, memoryMb, diskGb, start, end);
    }
}
