package kr.ac.pusan.pickle.inventory;

import kr.ac.pusan.pickle.support.RequestFixtures;
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
 * Defense-in-depth CHECK constraints (V11, and the OS catalog's in V62): direct JDBC
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
    private long imageId;
    private long requesterId;
    private long workspaceId;

    @BeforeEach
    void setUp() {
        orgId = SeedFixtures.seedOrgId(jdbc);
        nodeId = jdbc.queryForObject("select id from nodes where name = 'pve1'", Long.class);
        imageId = jdbc.queryForObject("select min(id) from os_images", Long.class);
        requesterId = SeedFixtures.orgadminId(jdbc);
        String slug = "chk-" + UUID.randomUUID().toString().substring(0, 8);
        workspaceId = jdbc.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, slug);
    }

    @Test
    void userWithOrgIdIsRejected() {
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
                .hasMessageContaining("chk_vm_request_details_positive_specs");
    }

    @Test
    void reversedRequestDatesAreRejected() {
        assertThatThrownBy(() -> insertRequest(1, 1024, 10, "2026-08-01", "2026-07-01"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_requests_date_order");
    }

    @Test
    void nonPositiveVmSpecIsRejected() {
        long requestId = insertRequest(1, 1024, 10, null, null);
        String hostname = "chk-vm-" + UUID.randomUUID().toString().substring(0, 12);
        assertThatThrownBy(() -> jdbc.update("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb)
                values (?, ?, ?, ?, ?, ?, ?, 1, 0, 10)
                """, nodeId, workspaceId, orgId, requestId, hostname, hostname, imageId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_vms_positive_specs");
    }

    @Test
    void partialGrantedSpecIsRejected() {
        long requestId = insertRequest(1, 1024, 10, null, null);
        // A granted spec that is present but incomplete (no image) must fail:
        // the constraint moved to the detail row with the columns it guards.
        assertThatThrownBy(() -> jdbc.update("""
                update vm_request_details
                   set granted_vcpu = 2, granted_memory_mb = 2048, granted_disk_gb = 20
                 where request_id = ?
                """, requestId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_vm_request_details_approved_granted");
    }

    @Test
    void rejectReviewWithNullGrantsIsAccepted() {
        // The mirror case: a REJECT leaves every granted column null and must
        // pass both the review's own constraints and the detail row's.
        long requestId = insertRequest(1, 1024, 10, null, null);
        int inserted = jdbc.update("""
                insert into request_reviews (request_id, reviewer_id, decision, comment)
                values (?, ?, 'REJECT', '반려 사유')
                """, requestId, requesterId);
        assertThat(inserted).isEqualTo(1);
    }

    @Test
    void malformedOsImageIdentityIsRejected() {
        assertThatThrownBy(() -> insertImage("Ubuntu", "24.04", "ubuntu"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_os_images_os_family");
        // a release label the documented version ordering could not parse
        assertThatThrownBy(() -> insertImage("ubuntu", "24.04 LTS", "ubuntu"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_os_images_os_version");
        // the guest account reaches Proxmox as the cloud-init user
        assertThatThrownBy(() -> insertImage("ubuntu", "24.04", "root user"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_os_images_ssh_username");
        assertThat(insertImage("rocky", "10", "rocky")).isEqualTo(1);
    }

    private int insertImage(String osFamily, String osVersion, String sshUsername) {
        return jdbc.update("""
                insert into os_images (name, display_name, os_family, os_version, ssh_username,
                                       proxmox_vmid, node_id, min_disk_gb, status)
                values (?, '제약 테스트 이미지', ?, ?, ?, 1009, ?, 10, 'DISABLED'::catalog_status)
                """, "chk-image-" + UUID.randomUUID().toString().substring(0, 8),
                osFamily, osVersion, sshUsername, nodeId);
    }

    private long insertRequest(int vcpu, int memoryMb, int diskGb, String start, String end) {
        return RequestFixtures.insertVmRequest(jdbc, workspaceId, orgId, requesterId, "제약 테스트",
                imageId, vcpu, memoryMb, diskGb, start, end);
    }
}
