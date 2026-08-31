package kr.ac.pusan.pickle.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminLlmUsageResponse;
import kr.ac.pusan.pickle.admin.dto.LlmGatewayStatusResponse;
import kr.ac.pusan.pickle.admin.dto.LlmLimitPressureResponse;
import kr.ac.pusan.pickle.admin.dto.LlmLimitReviewCollectionResponse;
import kr.ac.pusan.pickle.admin.dto.LlmLimitReviewResponse;
import kr.ac.pusan.pickle.admin.dto.LlmUsageConsumerLevel;
import kr.ac.pusan.pickle.admin.dto.LlmUsageConsumerResponse;
import kr.ac.pusan.pickle.admin.dto.LlmUsageConsumersResponse;
import kr.ac.pusan.pickle.admin.dto.LlmUsageDailyPointResponse;
import kr.ac.pusan.pickle.admin.dto.LlmUsageDemandResponse;
import kr.ac.pusan.pickle.admin.dto.LlmUsageQualityResponse;
import kr.ac.pusan.pickle.admin.dto.LlmUsageWindowResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.orgs.AdminOrgScope;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Bulk, repeatable-read aggregates for the administrator LLM usage screen. */
@Service
public class AdminLlmUsageService {

    private static final String TIMEZONE = "Asia/Seoul";
    private static final List<String> PRESSURE_REASONS = List.of(
            "quota_exhausted", "credit_exhausted", "rate_limit_requests",
            "rate_limit_tokens", "rate_limit_concurrency");

    private final JdbcTemplate jdbcTemplate;
    private final OrgRepository orgRepository;
    private final WorkspaceRepository workspaceRepository;
    private final AdminLlmObservabilityService observabilityService;
    private final Clock clock;

    public AdminLlmUsageService(JdbcTemplate jdbcTemplate, OrgRepository orgRepository,
            WorkspaceRepository workspaceRepository,
            AdminLlmObservabilityService observabilityService, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.orgRepository = orgRepository;
        this.workspaceRepository = workspaceRepository;
        this.observabilityService = observabilityService;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminLlmUsageResponse get(AuthenticatedUser actor, @Nullable UUID orgId,
            @Nullable UUID workspaceId, int days, int top) {
        if (days != 7 && days != 30 && days != 90) {
            throw ApiException.validationFailed(List.of(new FieldValidationError(
                    "days", "조회 기간은 7일, 30일, 90일 중 하나여야 합니다.")));
        }
        ScopeContext context = resolveScope(actor, orgId, workspaceId);
        Instant generatedAt = clock.instant();
        LocalDate to = ClockConfig.todayKst(clock);
        LocalDate from = to.minusDays(days - 1L);

        Map<LocalDate, UsageTotals> ninetyDays = dailyTotals(
                context, to.minusDays(89), to);
        LlmUsageDemandResponse demand = demand(ninetyDays, from, to);
        LlmUsageConsumersResponse consumers = consumers(context, actor, from, to, top);
        LlmLimitReviewCollectionResponse limitReview = limitReview(
                context, generatedAt, to, top);
        LlmUsageQualityResponse quality = quality(
                context, actor, generatedAt, from, to);
        return new AdminLlmUsageResponse(generatedAt, TIMEZONE, from, to, days,
                demand, consumers, limitReview, quality);
    }

    private ScopeContext resolveScope(AuthenticatedUser actor, @Nullable UUID orgId,
            @Nullable UUID workspaceId) {
        Long requestedOrgId = orgId == null ? null
                : orgRepository.findByPublicId(orgId).map(Org::getId).orElse(null);
        OrgScope scope = AdminOrgScope.read(actor, orgId, requestedOrgId);
        Workspace workspace = workspaceId == null ? null
                : workspaceRepository.findByPublicId(workspaceId).orElseThrow(
                        AdminLlmUsageService::workspaceNotFound);
        if (workspace == null || scope.isUnrestricted() || scope.orgIds().isEmpty()) {
            return new ScopeContext(scope, workspace);
        }

        String array = scope.arrayParam();
        Boolean visibleAssociation = jdbcTemplate.queryForObject("""
                select exists (
                    select 1 from requests r
                     where r.workspace_id = ? and %s
                    union all
                    select 1 from vms v
                     where v.workspace_id = ?
                       and v.status <> 'DELETED'::vm_status and %s)
                """.formatted(scope.guard("r.org_id"), scope.guard("v.org_id")), Boolean.class,
                workspace.getId(), array, array,
                workspace.getId(), array, array);
        if (!Boolean.TRUE.equals(visibleAssociation)) {
            throw workspaceNotFound();
        }
        return new ScopeContext(scope, workspace);
    }

    private Map<LocalDate, UsageTotals> dailyTotals(ScopeContext context,
            LocalDate from, LocalDate to) {
        SqlParts parts = scopedParts(context, "k.org_id", "k.workspace_id");
        List<Object> args = new ArrayList<>();
        args.add(from);
        args.add(to);
        args.addAll(parts.args());
        List<DailyRow> rows = jdbcTemplate.query("""
                select d.day,
                       coalesce(sum(d.requests), 0) as requests,
                       coalesce(sum(d.input_tokens), 0) as input_tokens,
                       coalesce(sum(d.output_tokens), 0) as output_tokens,
                       coalesce(sum(d.estimated_requests), 0) as estimated_requests,
                       coalesce(sum(d.token_axis_requests), 0) as token_axis_requests,
                       coalesce(sum(d.credit_axis_requests), 0) as credit_axis_requests,
                       coalesce(sum(d.unknown_axis_requests), 0) as unknown_axis_requests
                  from llm_usage_daily d
                  left join llm_api_keys k on k.id = d.key_id
                 where d.day >= ? and d.day <= ?
                """ + parts.clause() + """
                 group by d.day
                 order by d.day
                """, (rs, rowNum) -> new DailyRow(
                        rs.getObject("day", LocalDate.class), usageTotals(rs)),
                args.toArray());
        Map<LocalDate, UsageTotals> byDay = new HashMap<>();
        rows.forEach(row -> byDay.put(row.day(), row.totals()));
        return byDay;
    }

    private LlmUsageDemandResponse demand(Map<LocalDate, UsageTotals> byDay,
            LocalDate selectedFrom, LocalDate to) {
        List<LlmUsageWindowResponse> windows = List.of(7, 30, 90).stream()
                .map(days -> window(days, sum(byDay, to.minusDays(days - 1L), to)))
                .toList();
        List<LlmUsageDailyPointResponse> daily = new ArrayList<>();
        for (LocalDate day = selectedFrom; !day.isAfter(to); day = day.plusDays(1)) {
            UsageTotals totals = byDay.getOrDefault(day, UsageTotals.ZERO);
            daily.add(new LlmUsageDailyPointResponse(day, totals.requests(),
                    totals.inputTokens(), totals.outputTokens(), totals.estimatedRequests(),
                    totals.tokenAxisRequests(), totals.creditAxisRequests(),
                    totals.unknownAxisRequests(), axisCoverage(totals)));
        }
        return new LlmUsageDemandResponse(windows, List.copyOf(daily));
    }

    private static UsageTotals sum(Map<LocalDate, UsageTotals> byDay,
            LocalDate from, LocalDate to) {
        UsageTotals result = UsageTotals.ZERO;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            result = result.plus(byDay.getOrDefault(day, UsageTotals.ZERO));
        }
        return result;
    }

    private static LlmUsageWindowResponse window(int days, UsageTotals totals) {
        return new LlmUsageWindowResponse(days, totals.requests(), totals.inputTokens(),
                totals.outputTokens(), totals.estimatedRequests(), totals.tokenAxisRequests(),
                totals.creditAxisRequests(), totals.unknownAxisRequests(), axisCoverage(totals));
    }

    private LlmUsageConsumersResponse consumers(ScopeContext context, AuthenticatedUser actor,
            LocalDate from, LocalDate to, int top) {
        LlmUsageConsumerLevel level = context.workspace() != null
                ? LlmUsageConsumerLevel.KEY
                : (!actor.role().isOrgTier() && context.scope().isUnrestricted()
                        ? LlmUsageConsumerLevel.ORG : LlmUsageConsumerLevel.WORKSPACE);
        SqlParts parts = scopedParts(context, "k.org_id", "k.workspace_id");
        String dimensions = switch (level) {
            case ORG -> """
                    o.public_id as org_public_id, o.name as org_name,
                    null::uuid as workspace_public_id, null::text as workspace_name,
                    null::uuid as key_public_id, null::text as key_name
                    """;
            case WORKSPACE -> """
                    null::uuid as org_public_id, null::text as org_name,
                    w.public_id as workspace_public_id, w.name as workspace_name,
                    null::uuid as key_public_id, null::text as key_name
                    """;
            case KEY -> """
                    o.public_id as org_public_id, o.name as org_name,
                    w.public_id as workspace_public_id, w.name as workspace_name,
                    k.public_id as key_public_id, k.name as key_name
                    """;
        };
        String group = switch (level) {
            case ORG -> "o.id, o.public_id, o.name";
            case WORKSPACE -> "w.id, w.public_id, w.name";
            case KEY -> "o.id, o.public_id, o.name, w.id, w.public_id, w.name, "
                    + "k.id, k.public_id, k.name";
        };
        String order = switch (level) {
            case ORG -> "o.id";
            case WORKSPACE -> "w.id";
            case KEY -> "o.id, w.id, k.id";
        };
        List<Object> args = new ArrayList<>();
        args.add(from);
        args.add(to);
        args.addAll(parts.args());
        args.add(top);
        List<ConsumerRow> rows = jdbcTemplate.query("""
                select %s,
                       sum(d.requests) as requests,
                       sum(d.input_tokens) as input_tokens,
                       sum(d.output_tokens) as output_tokens,
                       count(*) over() as total_items
                  from llm_usage_daily d
                  join llm_api_keys k on k.id = d.key_id
                  join workspaces w on w.id = k.workspace_id
                  join orgs o on o.id = k.org_id
                 where d.day >= ? and d.day <= ?
                """.formatted(dimensions) + parts.clause() + " group by " + group
                + " order by requests desc, " + order + " limit ?",
                (rs, rowNum) -> consumerRow(rs), args.toArray());
        long total = rows.isEmpty() ? 0 : rows.getFirst().totalItems();
        return new LlmUsageConsumersResponse(level,
                rows.stream().map(ConsumerRow::item).toList(), total, total > rows.size());
    }

    private LlmLimitReviewCollectionResponse limitReview(ScopeContext context,
            Instant generatedAt, LocalDate today, int top) {
        SqlParts parts = scopedParts(context, "k.org_id", "k.workspace_id");
        List<Object> args = new ArrayList<>(parts.args());
        args.add(Timestamp.from(generatedAt));
        args.add(today);
        args.add(today);
        args.add(today.minusDays(6));
        args.add(today.plusDays(1));
        args.add(top);
        String sql = """
                with scoped_keys as (
                    select k.id, k.public_id, k.name, k.status, k.expires_at,
                           k.daily_tokens, k.quota_exhausted, k.credit_limit,
                           k.credit_limit_reset, k.openrouter_usage,
                           k.openrouter_limit_remaining, k.openrouter_usage_at,
                           (k.credit_limit > 0 and k.openrouter_key_hash is not null)
                               as credit_axis_connected,
                           k.org_id, k.workspace_id,
                           w.public_id as workspace_public_id,
                           w.name as workspace_name, o.public_id as org_public_id,
                           o.name as org_name, a.public_id as account_public_id,
                           a.name as account_name
                      from llm_api_keys k
                      join workspaces w on w.id = k.workspace_id
                      join orgs o on o.id = k.org_id
                      left join openrouter_accounts a on a.id = k.openrouter_account_id
                     where true
                """ + parts.clause() + """
                       and k.status in ('PENDING'::llm_api_key_status,
                           'ACTIVE'::llm_api_key_status, 'SUSPENDED'::llm_api_key_status)
                       and (k.expires_at is null or k.expires_at > ?)
                ), today_usage as (
                    select e.key_id,
                           coalesce(sum(e.input_tokens::bigint + e.output_tokens::bigint)
                               filter (where e.budget_axis = 'TOKEN'), 0) as today_tokens,
                           coalesce(sum(e.input_tokens::bigint + e.output_tokens::bigint)
                               filter (where e.budget_axis is null), 0)
                               as today_unknown_axis_tokens
                      from llm_usage_events e
                      join scoped_keys k on k.id = e.key_id
                     where e.requested_at >= ?::date::timestamp at time zone 'Asia/Seoul'
                       and e.requested_at < (?::date + 1)::timestamp at time zone 'Asia/Seoul'
                     group by e.key_id
                ), pressure as (
                    select e.key_id,
                           count(*) filter (where e.error_type = 'quota_exhausted') as quota,
                           count(*) filter (where e.error_type = 'credit_exhausted') as credit,
                           count(*) filter (where e.error_type = 'rate_limit_requests') as rpm,
                           count(*) filter (where e.error_type = 'rate_limit_tokens') as tpm,
                           count(*) filter (where e.error_type = 'rate_limit_concurrency')
                               as concurrency
                      from llm_usage_events e
                      join scoped_keys k on k.id = e.key_id
                     where e.requested_at >= ?::date::timestamp at time zone 'Asia/Seoul'
                       and e.requested_at < ?::date::timestamp at time zone 'Asia/Seoul'
                       and e.error_type in ('quota_exhausted', 'credit_exhausted',
                           'rate_limit_requests', 'rate_limit_tokens',
                           'rate_limit_concurrency')
                     group by e.key_id
                ), review as (
                    select k.*, coalesce(t.today_tokens, 0) as today_tokens,
                           coalesce(t.today_unknown_axis_tokens, 0)
                               as today_unknown_axis_tokens,
                           coalesce(p.quota, 0) as quota_pressure,
                           coalesce(p.credit, 0) as credit_pressure,
                           coalesce(p.rpm, 0) as rpm_pressure,
                           coalesce(p.tpm, 0) as tpm_pressure,
                           coalesce(p.concurrency, 0) as concurrency_pressure,
                           (k.quota_exhausted or coalesce(p.quota, 0) > 0
                               or coalesce(p.credit, 0) > 0) as actually_exhausted,
                           greatest(
                               case when k.daily_tokens > 0 then
                                   coalesce(t.today_tokens, 0)::numeric / k.daily_tokens end,
                               case when k.credit_limit > 0 and k.openrouter_usage is not null
                                   then k.openrouter_usage / k.credit_limit end) as utilization,
                           k.status::text as effective_status
                      from scoped_keys k
                      left join today_usage t on t.key_id = k.id
                      left join pressure p on p.key_id = k.id
                     where k.daily_tokens is not null or k.credit_limit > 0
                        or k.quota_exhausted or coalesce(p.quota, 0) > 0
                        or coalesce(p.credit, 0) > 0 or coalesce(p.rpm, 0) > 0
                        or coalesce(p.tpm, 0) > 0 or coalesce(p.concurrency, 0) > 0
                )
                select review.*, count(*) over() as total_items
                  from review
                 order by actually_exhausted desc, utilization desc nulls last, id desc
                 limit ?
                """;
        List<LimitRow> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> limitRow(rs), args.toArray());
        long total = rows.isEmpty() ? 0 : rows.getFirst().totalItems();
        return new LlmLimitReviewCollectionResponse(
                rows.stream().map(LimitRow::item).toList(), total, total > rows.size());
    }

    private LlmUsageQualityResponse quality(ScopeContext context, AuthenticatedUser actor,
            Instant generatedAt, LocalDate from, LocalDate to) {
        SqlParts parts = scopedParts(context, "k.org_id", "k.workspace_id");
        List<Object> aggregateArgs = new ArrayList<>();
        aggregateArgs.add(from);
        aggregateArgs.add(to);
        aggregateArgs.addAll(parts.args());
        QualityAggregate aggregate = jdbcTemplate.queryForObject("""
                select coalesce(sum(d.requests), 0) as total_requests,
                       coalesce(sum(d.estimated_requests), 0) as estimated_requests,
                       coalesce(sum(d.input_tokens + d.output_tokens), 0) as total_tokens,
                       case when count(*) = 0 then 0
                            when count(*) filter (where d.estimated_tokens is null) > 0 then null
                            else coalesce(sum(d.estimated_tokens), 0) end as estimated_tokens
                  from llm_usage_daily d
                  left join llm_api_keys k on k.id = d.key_id
                 where d.day >= ? and d.day <= ?
                """ + parts.clause(), (rs, rowNum) -> new QualityAggregate(
                        rs.getLong("total_requests"), rs.getLong("estimated_requests"),
                        rs.getLong("total_tokens"), nullableLong(rs, "estimated_tokens")),
                aggregateArgs.toArray());
        QualityAggregate safe = aggregate == null ? QualityAggregate.ZERO : aggregate;

        SqlParts rawParts = scopedParts(context, "k.org_id", "k.workspace_id");
        Instant latestUsage = jdbcTemplate.queryForObject("""
                select max(e.received_at)
                  from llm_usage_events e
                  left join llm_api_keys k on k.id = e.key_id
                 where true
                """ + rawParts.clause(), Instant.class, rawParts.args().toArray());

        SqlParts meterParts = scopedParts(context, "k.org_id", "k.workspace_id");
        CreditMeters meters = jdbcTemplate.queryForObject("""
                select count(*) as total,
                       count(k.openrouter_usage_at) as observed,
                       min(k.openrouter_usage_at) as oldest,
                       max(k.openrouter_usage_at) as latest
                  from llm_api_keys k
                 where k.credit_limit > 0
                """ + meterParts.clause(), (rs, rowNum) -> new CreditMeters(
                        rs.getLong("total"), rs.getLong("observed"),
                        instant(rs, "oldest"), instant(rs, "latest")),
                meterParts.args().toArray());
        CreditMeters safeMeters = meters == null ? CreditMeters.ZERO : meters;

        Instant rollupLastSuccess = jdbcTemplate.queryForObject(
                "select max(last_success_at) from llm_usage_rollup_state", Instant.class);
        LlmGatewayStatusResponse gateway = observabilityService.gatewayStatus(actor, generatedAt);
        Long unattributed = !actor.role().isOrgTier() && context.scope().isUnrestricted()
                && context.workspace() == null
                ? jdbcTemplate.queryForObject("""
                        select coalesce(sum(requests), 0) from llm_usage_daily
                         where key_id is null and day >= ? and day <= ?
                        """, Long.class, from, to)
                : null;
        return new LlmUsageQualityResponse(rollupLastSuccess, latestUsage,
                safeMeters.total(), safeMeters.observed(), safeMeters.oldest(),
                safeMeters.latest(), safe.totalRequests(), safe.estimatedRequests(),
                ratio(safe.estimatedRequests(), safe.totalRequests()), safe.totalTokens(),
                safe.estimatedTokens(), safe.estimatedTokens() == null ? null
                        : ratio(safe.estimatedTokens(), safe.totalTokens()),
                gateway.reportState(), gateway.usageQueueReportState(), gateway.lastContactAt(),
                gateway.lastUsageShipSuccessAt(), gateway.usageQueueObservedAt(),
                gateway.oldestUnshippedEventAt(), gateway.queuedUsageEvents(),
                gateway.queuedUsageBytes(), gateway.spoolWriteFailures(),
                gateway.usageShipFailures(), gateway.usageQueueScanFailures(), unattributed);
    }

    private SqlParts scopedParts(ScopeContext context, String orgColumn,
            String workspaceColumn) {
        StringBuilder clause = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (!context.scope().isUnrestricted()) {
            clause.append(" and ").append(context.scope().guard(orgColumn));
            args.add(context.scope().arrayParam());
            args.add(context.scope().arrayParam());
        }
        if (context.workspace() != null) {
            clause.append(" and ").append(workspaceColumn).append(" = ?");
            args.add(context.workspace().getId());
        }
        return new SqlParts(clause.toString(), List.copyOf(args));
    }

    private static UsageTotals usageTotals(ResultSet rs) throws SQLException {
        return new UsageTotals(rs.getLong("requests"), rs.getLong("input_tokens"),
                rs.getLong("output_tokens"), rs.getLong("estimated_requests"),
                rs.getLong("token_axis_requests"), rs.getLong("credit_axis_requests"),
                rs.getLong("unknown_axis_requests"));
    }

    private static ConsumerRow consumerRow(ResultSet rs) throws SQLException {
        return new ConsumerRow(new LlmUsageConsumerResponse(
                rs.getObject("org_public_id", UUID.class), rs.getString("org_name"),
                rs.getObject("workspace_public_id", UUID.class), rs.getString("workspace_name"),
                rs.getObject("key_public_id", UUID.class), rs.getString("key_name"),
                rs.getLong("requests"), rs.getLong("input_tokens"),
                rs.getLong("output_tokens")), rs.getLong("total_items"));
    }

    private static LimitRow limitRow(ResultSet rs) throws SQLException {
        List<LlmLimitPressureResponse> pressure = new ArrayList<>();
        addPressure(pressure, PRESSURE_REASONS.get(0), rs.getLong("quota_pressure"));
        addPressure(pressure, PRESSURE_REASONS.get(1), rs.getLong("credit_pressure"));
        addPressure(pressure, PRESSURE_REASONS.get(2), rs.getLong("rpm_pressure"));
        addPressure(pressure, PRESSURE_REASONS.get(3), rs.getLong("tpm_pressure"));
        addPressure(pressure, PRESSURE_REASONS.get(4), rs.getLong("concurrency_pressure"));
        String reset = rs.getString("credit_limit_reset");
        LlmLimitReviewResponse item = new LlmLimitReviewResponse(
                rs.getObject("public_id", UUID.class), rs.getString("name"),
                rs.getObject("org_public_id", UUID.class), rs.getString("org_name"),
                rs.getObject("workspace_public_id", UUID.class),
                rs.getString("workspace_name"),
                LlmApiKeyStatus.valueOf(rs.getString("effective_status")),
                nullableLong(rs, "daily_tokens"), rs.getLong("today_tokens"),
                rs.getLong("today_unknown_axis_tokens"),
                rs.getBoolean("quota_exhausted"), rs.getBigDecimal("credit_limit"),
                reset == null ? null : CreditLimitReset.valueOf(reset),
                rs.getBigDecimal("openrouter_usage"),
                rs.getBigDecimal("openrouter_limit_remaining"),
                instant(rs, "openrouter_usage_at"),
                rs.getBoolean("credit_axis_connected"),
                rs.getObject("account_public_id", UUID.class), rs.getString("account_name"),
                List.copyOf(pressure));
        return new LimitRow(item, rs.getLong("total_items"));
    }

    private static void addPressure(List<LlmLimitPressureResponse> items,
            String reason, long requests) {
        if (requests > 0) {
            items.add(new LlmLimitPressureResponse(reason, requests));
        }
    }

    private static @Nullable Double ratio(long numerator, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static @Nullable Double axisCoverage(UsageTotals totals) {
        return ratio(totals.tokenAxisRequests() + totals.creditAxisRequests(),
                totals.requests());
    }

    private static @Nullable Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static ApiException workspaceNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 워크스페이스를 찾을 수 없습니다.");
    }

    private record ScopeContext(OrgScope scope, @Nullable Workspace workspace) {
    }

    private record SqlParts(String clause, List<Object> args) {
    }

    private record DailyRow(LocalDate day, UsageTotals totals) {
    }

    private record UsageTotals(long requests, long inputTokens, long outputTokens,
            long estimatedRequests, long tokenAxisRequests, long creditAxisRequests,
            long unknownAxisRequests) {
        private static final UsageTotals ZERO = new UsageTotals(0, 0, 0, 0, 0, 0, 0);

        private UsageTotals plus(UsageTotals other) {
            return new UsageTotals(requests + other.requests, inputTokens + other.inputTokens,
                    outputTokens + other.outputTokens,
                    estimatedRequests + other.estimatedRequests,
                    tokenAxisRequests + other.tokenAxisRequests,
                    creditAxisRequests + other.creditAxisRequests,
                    unknownAxisRequests + other.unknownAxisRequests);
        }
    }

    private record ConsumerRow(LlmUsageConsumerResponse item, long totalItems) {
    }

    private record LimitRow(LlmLimitReviewResponse item, long totalItems) {
    }

    private record QualityAggregate(long totalRequests, long estimatedRequests,
            long totalTokens, @Nullable Long estimatedTokens) {
        private static final QualityAggregate ZERO = new QualityAggregate(0, 0, 0, 0L);
    }

    private record CreditMeters(long total, long observed, @Nullable Instant oldest,
            @Nullable Instant latest) {
        private static final CreditMeters ZERO = new CreditMeters(0, 0, null, null);
    }
}
