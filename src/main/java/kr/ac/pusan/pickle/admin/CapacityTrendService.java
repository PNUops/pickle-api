package kr.ac.pusan.pickle.admin;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.CapacityTrendPointResponse;
import kr.ac.pusan.pickle.admin.dto.CapacityTrendResponse;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocation over time (contract op {@code getAdminCapacityTrend}), scoped by
 * the same rule as the dashboard summary it sits beside.
 *
 * <p>No history table backs this. Each day is reconstructed from the {@code vms}
 * rows themselves: a VM counts on a day when it was created before that day
 * ended and was not yet deleted by then. Two properties of the schema make that
 * exact rather than approximate, and both are load-bearing:
 *
 * <ul>
 *   <li>a VM row is never physically deleted — deletion sets {@code deleted_at}
 *       and the DELETED status, so the row that describes a long-gone VM is
 *       still here to be counted on the days it lived;</li>
 *   <li>the allocated specification ({@code vcpu}, {@code memory_mb},
 *       {@code disk_gb}) is immutable for the life of the VM, so today's
 *       columns are also what the VM held on every past day.</li>
 * </ul>
 *
 * <p>The second one is a tripwire, not a guarantee: <b>the day a resize feature
 * lands, this reconstruction silently starts back-dating the new size over the
 * whole history</b>. A resize must therefore arrive with a per-VM allocation
 * history to read instead, and this class must stop reading the live columns.
 *
 * <p>A VM in ERROR state counts while it is not deleted. Its historical error
 * intervals are unknowable, and the alternative — dropping it entirely —
 * erases days it really did hold resources. The current-allocation surfaces
 * exclude ERROR rows, so during an incident these totals may sit slightly above
 * what {@link OrgHeadroomService} reports.
 *
 * <p>Days are KST calendar days, the product's contractual timezone. The series
 * is generated as plain dates and each boundary is turned into the instant of
 * KST midnight inside the query, so the comparison against the timestamptz
 * columns is exact; KST observes no daylight saving, so a fixed 24-hour day
 * holds.
 */
@Service
public class CapacityTrendService {

    private static final String TREND_SQL = """
            select d::date as day,
                   count(v.id) as vm_count,
                   coalesce(sum(v.vcpu), 0) as vcpu,
                   coalesce(sum(v.memory_mb), 0) as memory_mb,
                   coalesce(sum(v.disk_gb), 0) as disk_gb
              from generate_series(?::date::timestamp, ?::date::timestamp, interval '1 day') d
              left join vms v
                     on v.created_at < (d::date + 1)::timestamp at time zone 'Asia/Seoul'
                    and (v.deleted_at is null
                         or v.deleted_at >= (d::date + 1)::timestamp at time zone 'Asia/Seoul')
                    and %s
             group by d
             order by d
            """;

    private final JdbcTemplate jdbcTemplate;
    private final AdminSummaryService adminSummaryService;
    private final OrgHeadroomService orgHeadroomService;
    private final Clock clock;

    public CapacityTrendService(JdbcTemplate jdbcTemplate, AdminSummaryService adminSummaryService,
            OrgHeadroomService orgHeadroomService, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.adminSummaryService = adminSummaryService;
        this.orgHeadroomService = orgHeadroomService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CapacityTrendResponse trend(AuthenticatedUser actor, int days, UUID orgId) {
        OrgScope scope = adminSummaryService.resolveOrgId(actor, orgId);
        String orgs = scope.arrayParam();
        LocalDate to = ClockConfig.todayKst(clock);
        LocalDate from = to.minusDays(days - 1L);
        List<CapacityTrendPointResponse> points = jdbcTemplate.query(
                TREND_SQL.formatted(scope.guard("v.org_id")),
                (rs, rowNum) -> new CapacityTrendPointResponse(
                        rs.getObject("day", LocalDate.class),
                        rs.getLong("vm_count"),
                        rs.getLong("vcpu"),
                        rs.getLong("memory_mb"),
                        rs.getLong("disk_gb")),
                from, to, orgs, orgs);
        OrgHeadroomService.PlatformCapacity capacity = orgHeadroomService.capacity();
        return new CapacityTrendResponse(from, to, capacity.cpuThreads(), capacity.memoryMb(),
                capacity.diskGb(), points);
    }
}
