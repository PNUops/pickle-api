package kr.ac.pusan.pickle.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.NodeLiveResponse;
import kr.ac.pusan.pickle.admin.dto.NodeSummaryResponse;
import kr.ac.pusan.pickle.admin.dto.OrgDashboardSummaryResponse;
import kr.ac.pusan.pickle.admin.dto.OrgDashboardSummaryResponse.Attention;
import kr.ac.pusan.pickle.admin.dto.OrgDashboardSummaryResponse.RecentDecisions;
import kr.ac.pusan.pickle.admin.dto.OrgDashboardSummaryResponse.Resource;
import kr.ac.pusan.pickle.admin.dto.OrgDashboardSummaryResponse.TopWorkspace;
import kr.ac.pusan.pickle.admin.dto.SystemDashboardSummaryResponse;
import kr.ac.pusan.pickle.admin.dto.SystemDashboardSummaryResponse.IpPoolUsage;
import kr.ac.pusan.pickle.admin.dto.SystemDashboardSummaryResponse.LiveCoverage;
import kr.ac.pusan.pickle.admin.dto.SystemDashboardSummaryResponse.NodeRatio;
import kr.ac.pusan.pickle.admin.dto.SystemDashboardSummaryResponse.Tasks;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.ipam.IpPoolRepository;
import kr.ac.pusan.pickle.ipam.IpamService;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.provisioning.DriftFindingRepository;
import kr.ac.pusan.pickle.provisioning.DriftFindingStatus;
import kr.ac.pusan.pickle.proxmox.ProxmoxApiException;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.proxmox.dto.NodeStatusInfo;
import kr.ac.pusan.pickle.proxmox.dto.NodeStorageStatus;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard summaries: the org panel for ORG_ADMIN home /
 * SYS_ADMIN drill-in ({@code GET /admin/summary}) and the platform panel for
 * SYS_ADMIN ({@code GET /admin/system-summary}). Read-only aggregation; the
 * resource block reuses {@link OrgHeadroomService} and the node ratios reuse
 * {@link AdminNodeQueryService}, so dashboards judge by the same numbers as
 * the approval/node views. Day boundaries (expiry counts) are KST.
 */
@Service
public class AdminSummaryService {

    private static final Logger log = LoggerFactory.getLogger(AdminSummaryService.class);

    private final JdbcTemplate jdbcTemplate;
    private final OrgRepository orgRepository;
    private final OrgHeadroomService orgHeadroomService;
    private final AdminNodeQueryService adminNodeQueryService;
    private final DriftFindingRepository driftFindingRepository;
    private final IpPoolRepository ipPoolRepository;
    private final IpamService ipamService;
    private final NodeRepository nodeRepository;
    private final ProxmoxClient proxmoxClient;
    private final Clock clock;

    public AdminSummaryService(JdbcTemplate jdbcTemplate, OrgRepository orgRepository,
            OrgHeadroomService orgHeadroomService, AdminNodeQueryService adminNodeQueryService,
            DriftFindingRepository driftFindingRepository, IpPoolRepository ipPoolRepository,
            IpamService ipamService, NodeRepository nodeRepository, ProxmoxClient proxmoxClient,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.orgRepository = orgRepository;
        this.orgHeadroomService = orgHeadroomService;
        this.adminNodeQueryService = adminNodeQueryService;
        this.driftFindingRepository = driftFindingRepository;
        this.ipPoolRepository = ipPoolRepository;
        this.ipamService = ipamService;
        this.nodeRepository = nodeRepository;
        this.proxmoxClient = proxmoxClient;
        this.clock = clock;
    }

    /**
     * Org panel — or, for a SYS_ADMIN without an {@code orgId} drill-in, the
     * platform-wide aggregate in the same shape ({@code scope} unrestricted =
     * no org filter anywhere below; the console home calls it that way).
     */
    @Transactional(readOnly = true)
    public OrgDashboardSummaryResponse orgSummary(AuthenticatedUser actor, UUID orgId) {
        OrgScope scope = resolveOrgId(actor, orgId);
        String orgs = scope.arrayParam();
        LocalDate today = ClockConfig.todayKst(clock);
        Instant decidedSince = clock.instant().minus(Duration.ofDays(14));

        long pending = count("select count(*) from requests"
                + " where %s and status = 'SUBMITTED'".formatted(scope.guard("org_id")),
                orgs, orgs);
        RecentDecisions decisions = jdbcTemplate.queryForObject("""
                select count(*) filter (where r.decision = 'APPROVE') as approved,
                       count(*) filter (where r.decision = 'REJECT') as rejected
                  from request_reviews r
                  join requests q on q.id = r.request_id
                 where %s and r.created_at >= ?
                """.formatted(scope.guard("q.org_id")),
                (rs, rowNum) -> new RecentDecisions(rs.getLong("approved"),
                rs.getLong("rejected")), orgs, orgs,
                java.sql.Timestamp.from(decidedSince));

        OrgHeadroomService.HeadroomResult headroom = orgHeadroomService.headroom(scope);
        Resource resource = new Resource(headroom.allocated().vcpu(),
                headroom.allocated().memoryMb(), headroom.allocated().diskGb(),
                headroom.capacityVcpu(), headroom.capacityMemoryMb(), headroom.capacityDiskGb(),
                headroom.guidance());

        List<TopWorkspace> topWorkspaces = jdbcTemplate.query("""
                select g.public_id as workspace_public_id, v.workspace_id, g.name,
                       count(*) as vm_count
                  from vms v
                  join workspaces g on g.id = v.workspace_id
                 where %s and v.status <> 'DELETED'
                 group by g.public_id, v.workspace_id, g.name
                 order by vm_count desc, v.workspace_id
                 limit 10
                """.formatted(scope.guard("v.org_id")), (rs, rowNum) -> new TopWorkspace(
                rs.getObject("workspace_public_id", UUID.class), rs.getString("name"),
                rs.getLong("vm_count")), orgs, orgs);

        long published = count("""
                select count(*)
                  from routes r
                  join domains d on d.id = r.domain_id
                  join vms v on v.id = d.vm_id
                 where %s and r.status <> 'REMOVED'
                """.formatted(scope.guard("v.org_id")), orgs, orgs);
        long expiring30d = jdbcTemplate.queryForObject("""
                select count(*) from vms
                 where %s and end_date is not null
                   and end_date >= ? and end_date <= ?
                   and status not in ('DELETED', 'DELETING')
                """.formatted(scope.guard("org_id")),
                Long.class, orgs, orgs, today, today.plusDays(30));

        Attention attention = new Attention(
                count("""
                        select count(*) from provisioning_tasks t
                          join vms v on v.id = t.vm_id
                         where %s and t.status = 'FAILED'
                        """.formatted(scope.guard("v.org_id")), orgs, orgs),
                count("select count(*) from vms where %s"
                        .formatted(scope.guard("org_id"))
                        + " and status = 'NEEDS_ADMIN'", orgs, orgs),
                jdbcTemplate.queryForObject("""
                        select count(*) from vms
                         where %s
                           and end_date is not null and end_date < ?
                           and status not in ('DELETED', 'DELETING')
                        """.formatted(scope.guard("org_id")), Long.class, orgs, orgs, today));

        return new OrgDashboardSummaryResponse(pending, decisions,
                vmCountsByStatus(scope), resource, topWorkspaces,
                published, expiring30d, attention);
    }

    /**
     * Platform panel. Deliberately not {@code @Transactional}: it ends in a
     * live hypervisor probe per node, and a shared transaction would pin a
     * pooled database connection for the whole of that — long enough, with a
     * stalled pveproxy, for a refreshing dashboard to drain the pool and take
     * unrelated endpoints down with it. Nothing here needs one transaction:
     * every tile is an independent counter, each query and each collaborator
     * carries its own read transaction, and the entities read afterwards are
     * touched on basic columns only.
     *
     * <p>What one transaction did give the two node halves was one reading of
     * the node table. Without it, a status change landing mid-request would let
     * the ratio list and the live list describe different rows — the panel
     * would show a node as ACTIVE and unreachable at the same time, which reads
     * as an outage rather than as the parking the operator just did. So the
     * rows are read once here and both halves are built from that one list.
     */
    public SystemDashboardSummaryResponse systemSummary() {
        List<Node> nodeRows = nodeRepository.findAll(org.springframework.data.domain.Sort.by("id"));
        List<NodeRatio> nodes = adminNodeQueryService.listNodes(nodeRows).stream()
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
            pools.add(new IpPoolUsage(pool.getPublicId(), pool.getName(), pool.getCidr(),
                    usage.allocatedCount(), usage.freeCount()));
        });

        // Password-SSH is a per-VM opt-in exception to key-only identity, so the
        // count of opted-in live VMs stays visible on the system dashboard.
        long sshPasswordEnabledVms = count("""
                select count(*) from vms v
                  join vm_settings s on s.vm_id = v.id and s.key = 'ssh_password_enabled'
                 where s.value = 'true'::jsonb and v.status <> 'DELETED'
                """);

        List<NodeLiveResponse> live = nodesLive(nodeRows);
        return new SystemDashboardSummaryResponse(nodes, vmCountsByStatus(OrgScope.unrestricted()), tasks,
                notificationFailureCount(), certExpiring30d,
                driftFindingRepository.countByStatus(DriftFindingStatus.OPEN),
                sshPasswordEnabledVms, pools, live, liveCoverage(live));
    }

    /**
     * How much of the platform the live numbers actually cover. Each live
     * measurement is a per-node sum, and a sum over the nodes that answered is
     * not the platform total: one node whose storage read is refused leaves a
     * figure smaller than the truth, which an operator reads as free capacity
     * that is not there. The counts say how many nodes are behind each sum so a
     * client cannot present a subset as the whole — the same reason the org
     * headroom figures null a disk number no node measured.
     */
    private static LiveCoverage liveCoverage(List<NodeLiveResponse> live) {
        long memory = live.stream()
                .filter(node -> node.memTotalBytes() != null && node.memUsedBytes() != null)
                .count();
        long storage = live.stream()
                .filter(node -> node.storageTotalBytes() != null && node.storageUsedBytes() != null)
                .count();
        return new LiveCoverage(live.size(), (int) memory, (int) storage);
    }

    /**
     * What the hypervisor says about each node right now — the half of the
     * system panel the database cannot answer (real memory in use, live CPU,
     * how full the guest-disk pool actually is).
     *
     * <p>Every node is probed on its own and a failure is an answer, not an
     * error: a dead or unconfigured Proxmox endpoint yields
     * {@code reachable: false} and the panel still renders. A dashboard that
     * 500s when the hypervisor is down hides exactly the state it exists to
     * show. Both refusals the client can raise before a reply are caught for
     * that reason — the HTTP/transport failure and the unconfigured-token
     * refusal, which is the same "cannot ask PVE" from the operator's side.
     *
     * <p>The two probes are separate answers. Status decides reachability;
     * storage is a second call behind a second right ({@code Datastore.Audit}),
     * so losing it leaves the two storage fields null on a node that is still
     * reachable rather than blanking a tile that did answer.
     *
     * <p>Every node is asked, an OFFLINE one included. OFFLINE excludes a node
     * from new placements and leaves its existing guests running, so its RAM is
     * still spoken for; skipping the probe would drop that usage out of the
     * platform memory tile and show headroom the platform does not have. The
     * cost of asking a host that really is dead — one read timeout per
     * dashboard load — is accepted deliberately here and belongs to the
     * scale-out round, which will probe nodes in parallel under a time budget.
     */
    private List<NodeLiveResponse> nodesLive(List<Node> nodes) {
        List<NodeLiveResponse> live = new ArrayList<>();
        for (Node node : nodes) {
            NodeStatusInfo status;
            try {
                status = proxmoxClient.nodeStatus(node.getApiHost(), node.getName());
            } catch (ProxmoxApiException | IllegalStateException e) {
                log.warn("Node {} live probe failed: {}", node.getName(), e.getMessage());
                live.add(NodeLiveResponse.unreachable(node.getPublicId(), node.getName()));
                continue;
            }
            // The client hands back whatever the PVE envelope held, so a 200
            // with no data is possible and is not a reachable answer.
            if (status == null) {
                log.warn("Node {} live probe returned an empty status payload", node.getName());
                live.add(NodeLiveResponse.unreachable(node.getPublicId(), node.getName()));
                continue;
            }
            NodeStorageStatus storage = null;
            try {
                storage = guestStorage(node);
            } catch (ProxmoxApiException | IllegalStateException e) {
                log.warn("Node {} guest-storage read failed, tile keeps the status half: {}",
                        node.getName(), e.getMessage());
            }
            live.add(new NodeLiveResponse(node.getPublicId(), node.getName(), true,
                    status.memory() == null ? null : status.memory().total(),
                    status.memory() == null ? null : status.memory().used(),
                    status.cpu(),
                    storage == null ? null : storage.total(),
                    storage == null ? null : storage.used(),
                    clock.instant()));
        }
        return live;
    }

    /**
     * The pool guest disks are carved out of: the storage the node is
     * configured to clone onto, and failing that the first active thin pool the
     * token may see. Anything else on the host is somebody else's storage.
     */
    private NodeStorageStatus guestStorage(Node node) {
        List<NodeStorageStatus> storages = proxmoxClient.nodeStorage(node.getApiHost(),
                node.getName());
        return storages.stream()
                // node.storage is NOT NULL; the PVE-supplied name may be absent.
                .filter(storage -> node.getStorage().equals(storage.storage()))
                .findFirst()
                .orElseGet(() -> storages.stream()
                        .filter(storage -> "lvmthin".equals(storage.type()) && storage.isActive())
                        .findFirst()
                        .orElse(null));
    }

    /** FAILED notification deliveries (surfaced on the system dashboard). */
    private long notificationFailureCount() {
        return count("select count(*) from notifications where status = 'FAILED'");
    }

    /** All {@code VmStatus} keys, zero-filled, overlaid with actual counts (org-scoped or global). */
    private Map<String, Long> vmCountsByStatus(OrgScope scope) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (VmStatus status : VmStatus.values()) {
            counts.put(status.name(), 0L);
        }
        String orgs = scope.arrayParam();
        jdbcTemplate.query("select status::text as status, count(*) as cnt from vms"
                + " where %s group by status".formatted(scope.guard("org_id")), rs -> {
            counts.put(rs.getString("status"), rs.getLong("cnt"));
        }, orgs, orgs);
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
     * ORG_ADMIN is pinned to their own org (another org's id answers 404).
     * SYS_ADMIN: an explicit {@code orgId} must exist (unknown → 404), and no
     * {@code orgId} means the platform-wide aggregate (null scope) — the
     * console home calls the summary without a drill-in for both roles.
     *
     * <p>Package-private rather than private because the capacity trend is the
     * same panel over time and must be scoped by the identical rule; two copies
     * of a pinning rule is how one of them drifts open.
     */
    OrgScope resolveOrgId(AuthenticatedUser actor, UUID orgId) {
        Long requested = orgId == null ? null
                : orgRepository.findByPublicId(orgId).map(Org::getId).orElse(null);
        // Every admin tier reads every organisation (operator decision,
        // 2026-08-25). The orgId parameter is a filter for all of them now, not
        // a pin for some; writes stay scoped to the managed orgs.
        if (orgId == null) {
            return OrgScope.unrestricted(); // no drill-in → platform-wide
        }
        if (requested == null) {
            throw orgNotFound();
        }
        return OrgScope.of(requested);
    }

    private static ApiException orgNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 기관을 찾을 수 없습니다.");
    }
}
