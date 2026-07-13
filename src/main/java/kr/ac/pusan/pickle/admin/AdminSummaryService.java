package kr.ac.pusan.pickle.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.admin.dto.NodeSummaryResponse;
import kr.ac.pusan.pickle.admin.dto.OrgDashboardSummaryResponse;
import kr.ac.pusan.pickle.admin.dto.OrgDashboardSummaryResponse.Attention;
import kr.ac.pusan.pickle.admin.dto.OrgDashboardSummaryResponse.RecentDecisions;
import kr.ac.pusan.pickle.admin.dto.OrgDashboardSummaryResponse.Resource;
import kr.ac.pusan.pickle.admin.dto.OrgDashboardSummaryResponse.TopGroup;
import kr.ac.pusan.pickle.admin.dto.SystemDashboardSummaryResponse;
import kr.ac.pusan.pickle.admin.dto.SystemDashboardSummaryResponse.IpPoolUsage;
import kr.ac.pusan.pickle.admin.dto.SystemDashboardSummaryResponse.NodeRatio;
import kr.ac.pusan.pickle.admin.dto.SystemDashboardSummaryResponse.Tasks;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.ipam.IpPoolRepository;
import kr.ac.pusan.pickle.ipam.IpamService;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.provisioning.DriftFindingRepository;
import kr.ac.pusan.pickle.provisioning.DriftFindingStatus;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard summaries (M5, docs/plan/11): the org panel for ORG_ADMIN home /
 * SYS_ADMIN drill-in ({@code GET /admin/summary}) and the platform panel for
 * SYS_ADMIN ({@code GET /admin/system-summary}). Read-only aggregation; the
 * resource block reuses {@link OrgHeadroomService} and the node ratios reuse
 * {@link AdminNodeQueryService}, so dashboards judge by the same numbers as
 * the approval/node views. Day boundaries (expiry counts) are KST.
 */
@Service
public class AdminSummaryService {

    private final JdbcTemplate jdbcTemplate;
    private final OrgRepository orgRepository;
    private final OrgHeadroomService orgHeadroomService;
    private final AdminNodeQueryService adminNodeQueryService;
    private final DriftFindingRepository driftFindingRepository;
    private final IpPoolRepository ipPoolRepository;
    private final IpamService ipamService;
    private final Clock clock;

    public AdminSummaryService(JdbcTemplate jdbcTemplate, OrgRepository orgRepository,
            OrgHeadroomService orgHeadroomService, AdminNodeQueryService adminNodeQueryService,
            DriftFindingRepository driftFindingRepository, IpPoolRepository ipPoolRepository,
            IpamService ipamService, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.orgRepository = orgRepository;
        this.orgHeadroomService = orgHeadroomService;
        this.adminNodeQueryService = adminNodeQueryService;
        this.driftFindingRepository = driftFindingRepository;
        this.ipPoolRepository = ipPoolRepository;
        this.ipamService = ipamService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OrgDashboardSummaryResponse orgSummary(AuthenticatedUser actor, Long orgId) {
        long scopedOrgId = resolveOrgId(actor, orgId);
        LocalDate today = ClockConfig.todayKst(clock);
        Instant decidedSince = clock.instant().minus(Duration.ofDays(14));

        long pending = count("select count(*) from vm_requests where org_id = ?"
                + " and status = 'SUBMITTED'", scopedOrgId);
        RecentDecisions decisions = jdbcTemplate.queryForObject("""
                select count(*) filter (where r.decision = 'APPROVE') as approved,
                       count(*) filter (where r.decision = 'REJECT') as rejected
                  from vm_request_reviews r
                  join vm_requests q on q.id = r.request_id
                 where q.org_id = ? and r.created_at >= ?
                """, (rs, rowNum) -> new RecentDecisions(rs.getLong("approved"),
                rs.getLong("rejected")), scopedOrgId, java.sql.Timestamp.from(decidedSince));

        OrgHeadroomService.OrgHeadroom headroom = orgHeadroomService.headroom(scopedOrgId);
        Resource resource = new Resource(headroom.allocated().vcpu(),
                headroom.allocated().memoryMb(), headroom.allocated().diskGb(),
                headroom.capacityVcpu(), headroom.capacityMemoryMb(), headroom.guidance());

        List<TopGroup> topGroups = jdbcTemplate.query("""
                select v.group_id, g.name, count(*) as vm_count
                  from vms v
                  join groups g on g.id = v.group_id
                 where v.org_id = ? and v.status <> 'DELETED'
                 group by v.group_id, g.name
                 order by vm_count desc, v.group_id
                 limit 10
                """, (rs, rowNum) -> new TopGroup(rs.getLong("group_id"), rs.getString("name"),
                rs.getLong("vm_count")), scopedOrgId);

        long published = count("""
                select count(*)
                  from routes r
                  join domains d on d.id = r.domain_id
                  join vms v on v.id = d.vm_id
                 where v.org_id = ? and r.status <> 'REMOVED'
                """, scopedOrgId);
        long expiring30d = jdbcTemplate.queryForObject("""
                select count(*) from vms
                 where org_id = ? and end_date is not null
                   and end_date >= ? and end_date <= ?
                   and status not in ('DELETED', 'DELETING')
                """, Long.class, scopedOrgId, today, today.plusDays(30));

        Attention attention = new Attention(
                count("""
                        select count(*) from provisioning_tasks t
                          join vms v on v.id = t.vm_id
                         where v.org_id = ? and t.status = 'FAILED'
                        """, scopedOrgId),
                count("select count(*) from vms where org_id = ? and status = 'NEEDS_ADMIN'",
                        scopedOrgId),
                jdbcTemplate.queryForObject("""
                        select count(*) from vms
                         where org_id = ? and end_date is not null and end_date < ?
                           and status not in ('DELETED', 'DELETING')
                        """, Long.class, scopedOrgId, today));

        return new OrgDashboardSummaryResponse(pending, decisions,
                vmCountsByStatus(scopedOrgId), resource, topGroups,
                published, expiring30d, attention);
    }

    @Transactional(readOnly = true)
    public SystemDashboardSummaryResponse systemSummary() {
        List<NodeRatio> nodes = adminNodeQueryService.listNodes().stream()
                .map(AdminSummaryService::toNodeRatio)
                .toList();
        Tasks tasks = jdbcTemplate.queryForObject("""
                select count(*) filter (where status = 'RUNNING') as running,
                       count(*) filter (where status = 'RETRYING') as retrying,
                       count(*) filter (where status = 'NEEDS_ADMIN') as needs_admin,
                       count(*) filter (where status = 'FAILED' and updated_at >= ?) as failed24h
                  from provisioning_tasks
                """, (rs, rowNum) -> new Tasks(rs.getLong("running"), rs.getLong("retrying"),
                        rs.getLong("needs_admin"), rs.getLong("failed24h")),
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofHours(24))));

        long certExpiring30d = count("""
                select count(*) from certificates
                 where not_after is not null and not_after <= ?
                   and status in ('ACTIVE', 'RENEWING')
                """, java.sql.Timestamp.from(clock.instant().plus(Duration.ofDays(30))));

        List<IpPoolUsage> pools = new ArrayList<>();
        ipPoolRepository.findAll(org.springframework.data.domain.Sort.by("id")).forEach(pool -> {
            IpamService.PoolUsage usage = ipamService.poolUsage(pool.getId());
            pools.add(new IpPoolUsage(pool.getId(), pool.getName(), pool.getCidr(),
                    usage.allocatedCount(), usage.freeCount()));
        });

        return new SystemDashboardSummaryResponse(nodes, vmCountsByStatus(null), tasks,
                notificationFailureCount(), certExpiring30d,
                driftFindingRepository.countByStatus(DriftFindingStatus.OPEN), pools);
    }

    /**
     * FAILED notification deliveries. Defensive: the notifications table lands
     * with the api-A notification core (V16) — until that merge the count is 0
     * instead of a 500.
     */
    private long notificationFailureCount() {
        Boolean exists = jdbcTemplate.queryForObject(
                "select to_regclass('public.notifications') is not null", Boolean.class);
        if (exists == null || !exists) {
            return 0;
        }
        return count("select count(*) from notifications where status::text = 'FAILED'");
    }

    /** All {@code VmStatus} keys, zero-filled, overlaid with actual counts (org-scoped or global). */
    private Map<String, Long> vmCountsByStatus(Long orgId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (VmStatus status : VmStatus.values()) {
            counts.put(status.name(), 0L);
        }
        jdbcTemplate.query("select status::text as status, count(*) as cnt from vms"
                + " where (?::bigint is null or org_id = ?) group by status", rs -> {
            counts.put(rs.getString("status"), rs.getLong("cnt"));
        }, orgId, orgId);
        return counts;
    }

    private static NodeRatio toNodeRatio(NodeSummaryResponse node) {
        boolean warn = node.cpuOvercommitRatio() >= node.cpuWarnThreshold()
                || node.memoryAllocRatio() >= node.memoryWarnThreshold();
        return new NodeRatio(node.id(), node.name(), node.status(), node.cpuOvercommitRatio(),
                node.memoryAllocRatio(), warn);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    /**
     * ORG_ADMIN is pinned to their own org (another org's id answers 404);
     * SYS_ADMIN must name the org to drill into — a missing or unknown orgId
     * answers 404 too (the summary is per-org by contract).
     */
    private long resolveOrgId(AuthenticatedUser actor, Long orgId) {
        if (actor.role() == UserRole.ORG_ADMIN) {
            if (actor.orgId() == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                        "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
            }
            if (orgId != null && !orgId.equals(actor.orgId())) {
                throw orgNotFound();
            }
            return actor.orgId();
        }
        if (orgId == null || orgRepository.findById(orgId).isEmpty()) {
            throw orgNotFound();
        }
        return orgId;
    }

    private static ApiException orgNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 기관을 찾을 수 없습니다.");
    }
}
