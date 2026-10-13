package kr.ac.pusan.pickle.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.AdminResourceDeadlineDates;
import kr.ac.pusan.pickle.admin.dto.AdminResourceDeadlineKind;
import kr.ac.pusan.pickle.admin.dto.AdminResourceDeadlineResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.publishing.SubdomainPolicy;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** A metadata-only union whose count and rows share one repeatable-read snapshot. */
@Service
public class AdminResourceDeadlineQueryService {
    private static final Map<String, String> ORDERS = Map.of(
            "deadlineAt", "x.deadline_at asc, x.type asc, x.public_id asc",
            "-deadlineAt", "x.deadline_at desc, x.type asc, x.public_id asc");

    // Each branch chooses exactly one deadline; secondary dates never multiply rows.
    private static final String UNION = """
            with frame as (
              select ?::timestamptz as observed_at, ?::date as today,
                     ?::timestamptz as cutoff, ?::int as reserve_days
            ), resources as (
              select v.public_id, 'VM' as type, v.name,
                     v.status::text as source_status, v.status::text as status,
                     v.org_id, v.workspace_id, 'VM_USAGE_END' as deadline_kind,
                     (v.end_date + 1)::timestamp at time zone 'Asia/Seoul' as deadline_at,
                     v.end_date as inclusive_end_date, null::text as connection_status,
                     jsonb_build_object('startDate',v.start_date,'endDate',v.end_date,
                       'expiryStoppedAt',v.expiry_stopped_at,'deleteScheduledFor',v.delete_scheduled_for,
                       'deleteRequestedAt',v.delete_requested_at) as dates
                from vms v where v.status not in ('DELETED','DELETING') and v.end_date is not null
              union all
              select k.public_id, 'LLM_API_KEY', k.name, k.status::text,
                     case when k.expires_at <= f.observed_at then 'EXPIRED' else k.status::text end,
                     k.org_id, k.workspace_id, 'LLM_KEY_EXPIRY', k.expires_at,
                     case when (k.expires_at at time zone 'Asia/Seoul')::time = time '00:00:00'
                          then (k.expires_at at time zone 'Asia/Seoul')::date - 1 else null::date end,
                     null::text, jsonb_build_object('expiresAt',k.expires_at)
                from llm_api_keys k cross join frame f
               where k.status <> 'REVOKED' and k.expires_at is not null
              union all
              select d.public_id, 'DOMAIN', d.fqdn, d.status::text, d.status::text,
                     d.org_id, d.workspace_id,
                     case when d.released_at is null then 'DOMAIN_RENEWAL' else 'DOMAIN_RESERVATION' end,
                     case when d.released_at is null then d.renew_due_at
                          else d.released_at + (f.reserve_days * interval '24 hours') end,
                     null::date, null::text,
                     jsonb_build_object('renewDueAt',d.renew_due_at,'releasedAt',d.released_at,
                       'reservedUntil',case when d.released_at is null then null::timestamptz
                         else d.released_at + (f.reserve_days * interval '24 hours') end)
                from domains d cross join frame f
               where d.kind = 'EXTERNAL' and d.status <> 'REMOVED'
              union all
              select g.public_id, 'GPU', g.name, g.status::text, g.status::text,
                     g.org_id, g.workspace_id,
                     case when g.status = 'QUEUED' then 'GPU_GRANT_END' else 'GPU_LEASE_END' end,
                     case when g.status = 'QUEUED'
                          then (g.granted_end_date + 1)::timestamp at time zone 'Asia/Seoul'
                          else g.lease_ends_at end,
                     case when g.status = 'QUEUED' then g.granted_end_date else null::date end,
                     g.connection_status::text,
                     jsonb_build_object('grantedStartDate',g.granted_start_date,'grantedEndDate',g.granted_end_date,
                       'allocatedAt',g.allocated_at,'leaseEndsAt',g.lease_ends_at)
                from gpu_allocations g where g.status in ('QUEUED','ALLOCATED','RELEASING')
            )
            """;
    private final JdbcTemplate jdbc;
    private final OrgRepository orgs;
    private final WorkspaceRepository workspaces;
    private final SettingsService settings;
    private final Clock clock;
    private final ObjectMapper mapper;

    public AdminResourceDeadlineQueryService(JdbcTemplate jdbc, OrgRepository orgs,
            WorkspaceRepository workspaces, SettingsService settings, Clock clock, ObjectMapper mapper) {
        this.jdbc = jdbc; this.orgs = orgs; this.workspaces = workspaces;
        this.settings = settings; this.clock = clock; this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<AdminResourceDeadlineResponse> list(AuthenticatedUser actor,
            @Nullable UUID orgId, @Nullable UUID workspaceId, @Nullable ResourceType type,
            @Nullable AdminResourceDeadlineKind deadlineKind, @Nullable Integer expiringInDays,
            @Nullable Boolean expired, String sort, int page, int size) {
        StudentSupportPolicy.requireRead(actor);
        String order = ORDERS.get(sort);
        if (order == null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("sort", "지원하지 않는 정렬 기준입니다.")));
        }
        Long org = orgId == null ? null : orgs.findByPublicId(orgId).map(value -> value.getId()).orElse(null);
        OrgScope scope = StudentSupportPolicy.filterScope(actor, orgId, org);
        Long workspace = workspaceId == null ? null : workspaces.findByPublicId(workspaceId).map(value -> value.getId()).orElse(null);
        if (!scope.isUnrestricted() && scope.orgIds().isEmpty() || workspaceId != null && workspace == null) {
            return new PageResponse<>(List.of(), page, size, 0, 0);
        }
        // Truncate once before deriving dates: JDBC rounds submicrosecond instants forward.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDate today = LocalDate.ofInstant(now, ClockConfig.KST);
        LocalDate horizon = today.plusDays(expiringInDays == null ? 0 : expiringInDays);
        Instant cutoff = horizon.plusDays(1).atStartOfDay(ClockConfig.KST).toInstant();
        int reserveDays = settings.integer(SettingsService.PLATFORM_SUBDOMAIN_RESERVE_DAYS, SubdomainPolicy.DEFAULT_RESERVE_DAYS);
        List<Object> args = new ArrayList<>(List.of(OffsetDateTime.ofInstant(now, ZoneOffset.UTC), today,
                OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC), reserveDays));
        StringBuilder where = new StringBuilder(" where x.deadline_at is not null");
        if (!scope.isUnrestricted()) {
            where.append(" and ").append(scope.inList("x.org_id")); args.addAll(scope.orgIds());
        }
        if (workspace != null) { where.append(" and x.workspace_id = ?"); args.add(workspace); }
        if (type != null) { where.append(" and x.type = ?"); args.add(type.name()); }
        if (deadlineKind != null) { where.append(" and x.deadline_kind = ?"); args.add(deadlineKind.name()); }
        if (expiringInDays != null) {
            where.append("""
                     and x.deadline_at > f.observed_at and (
                       (x.deadline_kind in ('VM_USAGE_END','GPU_GRANT_END') and x.inclusive_end_date between f.today and ?::date)
                       or (x.deadline_kind = 'LLM_KEY_EXPIRY' and x.deadline_at <= f.cutoff)
                       or (x.deadline_kind in ('DOMAIN_RENEWAL','DOMAIN_RESERVATION','GPU_LEASE_END') and x.deadline_at < f.cutoff))
                    """); args.add(horizon);
        }
        if (Boolean.TRUE.equals(expired)) { where.append(" and x.deadline_at <= f.observed_at"); }
        String from = " from resources x cross join frame f";
        Long total = jdbc.queryForObject(UNION + "select count(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add((long) page * size);
        List<AdminResourceDeadlineResponse> content = jdbc.query(UNION + """
                select x.*, o.public_id as org_public_id, o.name as org_name,
                       w.public_id as workspace_public_id, w.name as workspace_name,
                       f.observed_at, x.deadline_at <= f.observed_at as overdue
                """ + from + " left join orgs o on o.id=x.org_id left join workspaces w on w.id=x.workspace_id"
                + where + " order by " + order + " limit ? offset ?", this::row, pageArgs.toArray());
        long count = total == null ? 0 : total;
        return new PageResponse<>(content, page, size, count, (int) ((count + size - 1) / size));
    }

    private AdminResourceDeadlineResponse row(ResultSet rs, int number) throws SQLException {
        return new AdminResourceDeadlineResponse(rs.getObject("public_id", UUID.class),
                ResourceType.valueOf(rs.getString("type")), rs.getString("name"),
                rs.getString("source_status"), rs.getString("status"),
                AdminResourceDeadlineKind.valueOf(rs.getString("deadline_kind")),
                rs.getTimestamp("deadline_at").toInstant(), rs.getObject("inclusive_end_date", LocalDate.class),
                rs.getBoolean("overdue"), rs.getTimestamp("observed_at").toInstant(),
                rs.getObject("workspace_public_id", UUID.class), rs.getString("workspace_name"),
                rs.getObject("org_public_id", UUID.class), rs.getString("org_name"), rs.getString("connection_status"),
                mapper.readValue(rs.getString("dates"), AdminResourceDeadlineDates.class));
    }
}
