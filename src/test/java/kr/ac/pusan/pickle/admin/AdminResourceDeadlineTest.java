package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The actual paged read across four persisted families, with a controlled KST clock. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({EmbeddedPostgresConfig.class, AdminResourceDeadlineTest.FixedClockConfiguration.class})
class AdminResourceDeadlineTest {
    private static final Instant NOW = Instant.parse("2026-03-10T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final Instant CUTOFF = Instant.parse("2026-03-17T15:00:00Z");
    private static final String PATH = "/api/v1/admin/resource-deadlines";
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MutableClock clock;
    private long org;
    private long workspace;
    private long owner;
    private long node;
    private long image;
    private UUID workspaceId;
    private AuthenticatedUser admin;

    static final class MutableClock extends Clock {
        private Instant value = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean @Primary MutableClock deadlineClock() { return new MutableClock(); }
    }

    @BeforeEach
    void setup() {
        clock.value = NOW;
        org = jdbc.queryForObject("insert into orgs(name) values (?) returning id", Long.class, "Deadline org " + UUID.randomUUID());
        workspace = jdbc.queryForObject("insert into workspaces(kind,name) values ('COURSE',?) returning id", Long.class, "Deadline course " + UUID.randomUUID());
        workspaceId = pub("workspaces", workspace);
        owner = SeedFixtures.sysadminId(jdbc);
        node = jdbc.queryForObject("select min(id) from nodes", Long.class);
        image = jdbc.queryForObject("select min(id) from os_images", Long.class);
        admin = actor(UserRole.SYS_ADMIN);
    }

    @Test
    void pagesAllFamiliesWithExactTotalAndKeepsTotalOnEmptyOutOfRangePage() throws Exception {
        for (int i = 0; i < 7; i++) {
            vm("STOPPED", TODAY.plusDays(i));
            key("ACTIVE", NOW.plusSeconds(i + 1));
            domain("ACTIVE", NOW.plusSeconds(i + 1), null, workspace, org);
            gpu("QUEUED", TODAY.plusDays(i), null);
        }
        JsonNode first = list(admin, Map.of("page", "0", "size", "20"));
        assertThat(first.path("totalElements").asLong()).isEqualTo(28);
        assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        assertThat(first.path("content").size()).isEqualTo(20);
        JsonNode next = list(admin, Map.of("page", "1", "size", "20"));
        assertThat(next.path("content").size()).isEqualTo(8);
        assertThat(list(admin, Map.of("page", "9")).path("totalElements").asLong()).isEqualTo(28);
        assertThat(list(admin, Map.of("page", "9")).path("content").size()).isZero();
        assertThat(first.path("content")).isEqualTo(list(admin, Map.of("page", "0", "size", "20")).path("content"));
    }

    @Test
    void usesInclusiveDatesAndKeyCutoffButExclusiveInstantCalendarBoundary() throws Exception {
        vm("STOPPED", TODAY); vm("RUNNING", TODAY.plusDays(7)); vm("STOPPED", TODAY.plusDays(8));
        gpu("QUEUED", TODAY.plusDays(7), null); gpu("QUEUED", TODAY.plusDays(8), null);
        UUID atKeyCutoff = key("ACTIVE", CUTOFF);
        UUID beforeKeyCutoff = key("ACTIVE", CUTOFF.minusNanos(1000));
        key("ACTIVE", CUTOFF.plusNanos(1000));
        domain("ACTIVE", CUTOFF.minusNanos(1000), null, workspace, org);
        domain("ACTIVE", CUTOFF, null, workspace, org);
        gpu("ALLOCATED", TODAY.plusDays(9), CUTOFF.minusNanos(1000));
        gpu("ALLOCATED", TODAY.plusDays(9), CUTOFF);
        JsonNode response = list(admin, Map.of("expiringInDays", "7"));
        assertThat(response.path("totalElements").asLong()).isEqualTo(7);
        JsonNode exact = row(response, atKeyCutoff);
        assertThat(exact.path("inclusiveEndDate").asString()).isEqualTo("2026-03-17");
        assertThat(row(response, beforeKeyCutoff).path("inclusiveEndDate").isNull()).isTrue();
        assertThat(exact.path("observedAt").asString()).isEqualTo(NOW.toString());
        assertThat(exact.path("overdue").asBoolean()).isFalse();
        assertThat(list(admin, Map.of("expiringInDays", "7", "expired", "true")).path("totalElements").asLong()).isZero();
    }

    @Test
    void marksOverdueAtExactInstantAndOnlyAfterInclusiveDateEndsInKst() throws Exception {
        vm("STOPPED", TODAY.minusDays(1)); vm("RUNNING", TODAY);
        gpu("QUEUED", TODAY.minusDays(1), null); gpu("QUEUED", TODAY, null);
        UUID expiredKey = key("SUSPENDED", NOW);
        key("ACTIVE", NOW.plusSeconds(1));
        domain("ACTIVE", NOW, null, workspace, org);
        gpu("RELEASING", TODAY.plusDays(1), NOW);
        JsonNode overdue = list(admin, Map.of("expired", "true"));
        assertThat(overdue.path("totalElements").asLong()).isEqualTo(5);
        assertThat(row(overdue, expiredKey).path("sourceStatus").asString()).isEqualTo("SUSPENDED");
        assertThat(row(overdue, expiredKey).path("status").asString()).isEqualTo("EXPIRED");
        clock.value = Instant.parse("2026-03-10T15:00:00Z");
        assertThat(list(admin, Map.of("expired", "true")).path("totalElements").asLong()).isEqualTo(8);
    }

    @Test
    void excludesTerminalAndMissingPrimaryRowsWithoutChangingPhysicalState() throws Exception {
        vm("DELETED", TODAY); vm("DELETING", TODAY); vm("STOPPED", null);
        key("REVOKED", NOW); key("ACTIVE", null);
        domain("REMOVED", NOW, null, workspace, org);
        gpu("CANCELED", TODAY, null); gpu("RELEASED", TODAY, null); gpu("QUEUED", null, null);
        UUID releasing = gpu("RELEASING", TODAY, NOW.minusSeconds(1));
        JsonNode response = list(admin, Map.of());
        assertThat(response.path("totalElements").asLong()).isEqualTo(1);
        assertThat(row(response, releasing).path("status").asString()).isEqualTo("RELEASING");
        assertThat(row(response, releasing).path("deadlineKind").asString()).isEqualTo("GPU_LEASE_END");
        assertThat(row(response, releasing).path("dates").path("grantedEndDate").asString()).isEqualTo(TODAY.toString());
        assertThat(jdbc.queryForObject("select status::text from gpu_allocations where public_id=?", String.class, releasing)).isEqualTo("RELEASING");
    }

    @Test
    void switchesReleasedExternalDomainToReservationAndUsesCurrentExactDaySetting() throws Exception {
        String prior = jdbc.queryForList("select value::text from settings where key='platform_subdomain_reserve_days'", String.class).stream().findFirst().orElse(null);
        jdbc.update("insert into settings(key,value) values ('platform_subdomain_reserve_days','2'::jsonb) on conflict(key) do update set value=excluded.value");
        try {
            Instant released = NOW.minusSeconds(86400);
            UUID id = domain("ACTIVE", NOW.minusSeconds(100), released, workspace, org);
            JsonNode response = list(admin, Map.of());
            assertThat(response.path("totalElements").asLong()).isEqualTo(1);
            JsonNode item = row(response, id);
            assertThat(item.path("deadlineKind").asString()).isEqualTo("DOMAIN_RESERVATION");
            assertThat(item.path("deadlineAt").asString()).isEqualTo(NOW.plusSeconds(86400).toString());
            assertThat(item.path("dates").path("renewDueAt").asString()).isEqualTo(NOW.minusSeconds(100).toString());
            assertThat(item.path("overdue").asBoolean()).isFalse();
        } finally {
            if (prior == null) jdbc.update("delete from settings where key='platform_subdomain_reserve_days'");
            else jdbc.update("update settings set value=?::jsonb where key='platform_subdomain_reserve_days'", prior);
        }
    }

    @Test
    void retainsReleasedDomainWithoutInstitutionOrWorkspaceContext() throws Exception {
        UUID id = domain("ACTIVE", NOW.minusSeconds(1), NOW.minusSeconds(86400), null, null);
        JsonNode response = listWithoutWorkspace(admin, Map.of("type", "DOMAIN", "deadlineKind", "DOMAIN_RESERVATION"));
        JsonNode item = row(response, id);
        assertThat(item.path("workspaceId").isNull()).isTrue();
        assertThat(item.path("orgId").isNull()).isTrue();
    }

    @Test
    void supportsSixGlobalReadersAndRejectsOrdinaryUserAndKeepsUnknownFiltersEmpty() throws Exception {
        UUID id = vm("STOPPED", TODAY);
        for (UserRole role : List.of(UserRole.ORG_VIEWER, UserRole.ORG_MANAGER, UserRole.ORG_ADMIN, UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN)) {
            assertThat(row(list(actor(role), Map.of()), id).path("id").asString()).isEqualTo(id.toString());
            assertThat(list(actor(role), Map.of("orgId", pub("orgs", org).toString())).path("totalElements").asLong()).isEqualTo(1);
        }
        mvc.perform(get(PATH).with(authentication(auth(actor(UserRole.USER))))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/admin/vms/" + id + "/period")
                .with(authentication(auth(actor(UserRole.ORG_ADMIN))))
                .contentType("application/json").content("{\"endDate\":\"2026-03-20\"}"))
                .andExpect(status().isNotFound());
        assertThat(list(admin, Map.of("orgId", SeedFixtures.UNKNOWN_ID.toString())).path("totalElements").asLong()).isZero();
        assertThat(listWithoutWorkspace(admin, Map.of("workspaceId", SeedFixtures.UNKNOWN_ID.toString())).path("totalElements").asLong()).isZero();
    }

    @Test
    void filtersKindAndTypeAndStabilizesEqualDeadlinesWithPublicIds() throws Exception {
        vm("STOPPED", TODAY); key("ACTIVE", NOW.plusSeconds(1));
        UUID a = domain("ACTIVE", NOW.plusSeconds(1), null, workspace, org);
        UUID b = domain("ACTIVE", NOW.plusSeconds(1), null, workspace, org);
        JsonNode rows = list(admin, Map.of("type", "DOMAIN", "deadlineKind", "DOMAIN_RENEWAL"));
        assertThat(rows.path("totalElements").asLong()).isEqualTo(2);
        assertThat(rows.path("content").get(0).path("id").asString()).isEqualTo(a.toString().compareTo(b.toString()) < 0 ? a.toString() : b.toString());
        assertThat(list(admin, Map.of("type", "DOMAIN", "deadlineKind", "VM_USAGE_END")).path("totalElements").asLong()).isZero();
    }

    @Test
    void validatesBoundedCalendarWindowAndSortWithoutChangingExistingMutationBounds() throws Exception {
        for (String value : List.of("0", "366")) {
            mvc.perform(get(PATH).param("expiringInDays", value).with(authentication(auth(admin)))).andExpect(status().isUnprocessableContent());
        }
        mvc.perform(get(PATH).param("sort", "sourceStatus").with(authentication(auth(admin)))).andExpect(status().isUnprocessableContent());
    }

    @Test
    void sortsPrimaryDeadlinesInBothDirectionsAndPreservesVmActionContext() throws Exception {
        UUID early = key("ACTIVE", NOW.plusSeconds(1));
        UUID later = vm("STOPPED", TODAY.plusDays(30));
        jdbc.update("update vms set start_date=?,expiry_stopped_at=?,delete_scheduled_for=? where public_id=?",
                TODAY.minusDays(30), Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW.plusSeconds(86400)), later);
        JsonNode ascending = list(admin, Map.of("sort", "deadlineAt"));
        JsonNode descending = list(admin, Map.of("sort", "-deadlineAt"));
        assertThat(ascending.path("content").get(0).path("id").asString()).isEqualTo(early.toString());
        assertThat(descending.path("content").get(0).path("id").asString()).isEqualTo(later.toString());
        JsonNode vm = row(descending, later);
        assertThat(vm.path("sourceStatus").asString()).isEqualTo("STOPPED");
        assertThat(vm.path("dates").path("expiryStoppedAt").asString()).isEqualTo(NOW.minusSeconds(60).toString());
        assertThat(vm.path("dates").path("deleteScheduledFor").asString()).isEqualTo(NOW.plusSeconds(86400).toString());
        assertThat(list(admin, Map.of("expiringInDays", "7")).path("totalElements").asLong()).isEqualTo(1);
        assertThat(list(admin, Map.of("expiringInDays", "30")).path("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void keepsDateAndObservedInstantBeforeMidnightAtDatabaseMicrosecondPrecision() throws Exception {
        clock.value = Instant.parse("2026-03-10T14:59:59.999999800Z");
        UUID vm = vm("STOPPED", TODAY);
        gpu("QUEUED", TODAY, null);
        JsonNode upcoming = list(admin, Map.of("expiringInDays", "7"));
        assertThat(upcoming.path("totalElements").asLong()).isEqualTo(2);
        JsonNode item = row(upcoming, vm);
        assertThat(item.path("observedAt").asString()).isEqualTo("2026-03-10T14:59:59.999999Z");
        assertThat(item.path("overdue").asBoolean()).isFalse();
        assertThat(list(admin, Map.of("expired", "true")).path("totalElements").asLong()).isZero();
    }

    private JsonNode row(JsonNode response, UUID id) {
        for (JsonNode item : response.path("content")) if (id.toString().equals(item.path("id").asString())) return item;
        throw new AssertionError("missing resource " + id);
    }
    private JsonNode list(AuthenticatedUser actor, Map<String,String> params) throws Exception {
        var request = get(PATH).param("workspaceId", workspaceId.toString()).with(authentication(auth(actor)));
        params.forEach(request::param);
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode listWithoutWorkspace(AuthenticatedUser actor, Map<String,String> params) throws Exception {
        var request = get(PATH).with(authentication(auth(actor))); params.forEach(request::param);
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private AuthenticatedUser actor(UserRole role) {
        return new AuthenticatedUser(owner, pub("users", owner), "deadline@example.invalid", role, Map.of());
    }
    private UsernamePasswordAuthenticationToken auth(AuthenticatedUser actor) {
        return UsernamePasswordAuthenticationToken.authenticated(actor, "test", List.of(new SimpleGrantedAuthority("ROLE_" + actor.role().name())));
    }
    private UUID pub(String table, long id) { return SeedFixtures.publicId(jdbc, table, id); }
    private long request(String type) {
        return jdbc.queryForObject("insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name) values (?::resource_type,?,?,?,'Deadline test','Deadline test') returning id", Long.class, type, workspace, org, owner);
    }
    private UUID vm(String status, LocalDate end) {
        long req = RequestFixtures.insertVmRequest(jdbc, workspace, org, owner, "Deadline test", image);
        long id = jdbc.queryForObject("insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status,end_date) values (?,?,?,?,?,?,?,1,1024,32,?::vm_status,?) returning id", Long.class, node, workspace, org, req, "Deadline VM", "deadline-"+UUID.randomUUID(), image, status, end);
        return pub("vms", id);
    }
    private UUID key(String status, Instant expiry) {
        long id = jdbc.queryForObject("insert into llm_api_keys(workspace_id,org_id,request_id,name,status,expires_at,created_by) values (?,?,?,'Deadline key',?::llm_api_key_status,?,?) returning id", Long.class, workspace, org, request("LLM_API_KEY"), status, expiry == null ? null : Timestamp.from(expiry), owner);
        return pub("llm_api_keys", id);
    }
    private UUID domain(String status, Instant renewal, Instant release, Long ws, Long institution) {
        long id = jdbc.queryForObject("insert into domains(workspace_id,org_id,kind,fqdn,status,renew_due_at,released_at) values (?,?,'EXTERNAL',?,?::domain_status,?,?) returning id", Long.class, ws, institution, "deadline-"+UUID.randomUUID()+".example.com", status, Timestamp.from(renewal), release == null ? null : Timestamp.from(release));
        return pub("domains", id);
    }
    private UUID gpu(String status, LocalDate grantEnd, Instant lease) {
        Long card = lease == null ? null : jdbc.queryForObject("insert into gpus(node_id,mapping_name,model,vram_mb,status) values (?,?,'Deadline card',1024,'ACTIVE') returning id", Long.class, node, "deadline-"+UUID.randomUUID());
        long id = jdbc.queryForObject("insert into gpu_allocations(request_id,workspace_id,org_id,name,status,granted_lease_hours,granted_end_date,gpu_id,allocated_at,lease_ends_at) values (?,?,?,'Deadline allocation',?::gpu_allocation_status,1,?,?,?,?) returning id", Long.class, request("GPU"), workspace, org, status, grantEnd, card, lease == null ? null : Timestamp.from(NOW.minusSeconds(3600)), lease == null ? null : Timestamp.from(lease));
        return pub("gpu_allocations", id);
    }
}
