package kr.ac.pusan.pickle.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse;
import kr.ac.pusan.pickle.admin.dto.LlmConsumerSort;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse.CapacityConsumer;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse.CapacityConsumers;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse.CapacityCounts;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse.CapacityCurrentLimits;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse.CapacityGroup;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse.CapacityQuality;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse.CapacityRawHistoryState;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse.CapacityReason;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse.CapacityReasonCategory;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse.CapacityReference;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse.CapacityReferenceCollection;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse.CapacitySelectors;
import kr.ac.pusan.pickle.admin.dto.AdminLlmCapacityResponse.CapacitySeriesPoint;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.llm.LlmUsageRetentionPolicy;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import kr.ac.pusan.pickle.observation.ObservationReader;
import kr.ac.pusan.pickle.common.time.DatabaseTimestamps;

/** One scoped raw relation feeds every demand aggregate and exact consumer page. */
@Service
public class AdminLlmCapacityService {
    private static final int REFERENCE_LIMIT = 200;
    private static final String COUNTS = """
            count(*) as requests,
            count(*) filter(where status='OK') as succeeded,
            count(*) filter(where status='RATE_LIMITED') as rate_limited,
            count(*) filter(where status='CANCELED') as canceled,
            count(*) filter(where status is null or status not in ('OK','RATE_LIMITED','CANCELED')) as failed,
            coalesce(sum(input_tokens::bigint),0) as input_tokens,
            coalesce(sum(output_tokens::bigint),0) as output_tokens,
            count(*) filter(where estimated) as estimated_requests,
            count(*) filter(where budget_axis='TOKEN') as token_axis_requests,
            count(*) filter(where budget_axis='CREDIT') as credit_axis_requests,
            count(*) filter(where budget_axis is null) as unknown_axis_requests,
            count(*) filter(where key_id is null) as unknown_key_requests,
            count(*) filter(where public_model_name is null) as unknown_model_requests,
            count(*) filter(where upstream_ref is null) as unknown_final_upstream_requests,
            sum(cost_usd) as cost_usd,
            count(*) filter(where cost_usd is not null) as priced_requests,
            coalesce(bool_and(finance_visible),true) as finance_visible
            """;
    private static final String CATEGORY = """
            case
             when error_type in ('quota_exhausted','credit_exhausted','rate_limit_requests','rate_limit_tokens','rate_limit_concurrency') then 'USER_QUOTA'
             when error_type='endpoint_not_allowed' then 'ENDPOINT_POLICY'
             when error_type in ('model_not_found','model_not_allowed','credit_model_not_allowed','model_not_available_on_route','preset_not_allowed','router_model_not_supported') then 'MODEL_POLICY'
             when error_type='large_request_busy' then 'LARGE_REQUEST'
             when error_type='server_busy' and upstream_ref is null and attempts=0 and status='RATE_LIMITED' then 'GLOBAL_ADMISSION'
             when upstream_ref is not null and error_type in ('server_busy','upstream_rejected','upstream_error','upstream_timeout','upstream_response_too_large','upstream_read_failed','upstream_invalid_response','upstream_stream_interrupted','upstream_stream_unterminated','upstream_chunk_unreadable','request_deadline_exceeded') then 'PROVIDER'
             when error_type in ('request_too_large','input_too_long','output_limit_exceeded','invalid_json','unsupported_parameter','invalid_parameter_value','missing_parameter','unknown_endpoint','method_not_allowed','streaming_not_supported') then 'INPUT'
             when error_type in ('missing_api_key','invalid_api_key','api_key_expired','api_key_revoked','account_suspended') then 'AUTHENTICATION'
             when error_type in ('credit_pending','credit_unavailable','service_disabled') then 'SERVICE'
             when status='CANCELED' then 'CANCELED'
             else 'UNCLASSIFIED' end
            """;

    private final JdbcTemplate jdbc;
    private final OrgRepository orgs;
    private final InstitutionLlmQueryAccess access;
    private final AdminLlmObservabilityService observations;
    private final LlmUsageRetentionPolicy retention;
    private final Clock clock;
    private final ObservationReader observationReader;

    public AdminLlmCapacityService(JdbcTemplate jdbc, OrgRepository orgs, InstitutionLlmQueryAccess access,
            AdminLlmObservabilityService observations, LlmUsageRetentionPolicy retention, Clock clock, ObservationReader observationReader) {
        this.jdbc = jdbc; this.orgs = orgs; this.access = access; this.observations = observations;
        this.retention = retention; this.clock = clock;
        this.observationReader = observationReader;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminLlmCapacityResponse get(AuthenticatedUser actor, Instant from, Instant to,
            @Nullable UUID orgId, @Nullable UUID workspaceId, @Nullable UUID upstreamId,
            @Nullable String model, @Nullable String error, boolean errorTypeMissing,
            @Nullable CapacityReasonCategory reasonCategory, CapacityGroup group,
            int page, int size, @Nullable Integer requestedBucket, @Nullable UUID telemetrySourceId) {
        return get(actor, from, to, orgId, workspaceId, upstreamId, model, error, errorTypeMissing,
                reasonCategory, group, page, size, requestedBucket, telemetrySourceId, null, LlmConsumerSort.REQUESTS_DESC);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminLlmCapacityResponse get(AuthenticatedUser actor, Instant from, Instant to,
            @Nullable UUID orgId, @Nullable UUID workspaceId, @Nullable UUID upstreamId,
            @Nullable String model, @Nullable String error, boolean errorTypeMissing,
            @Nullable CapacityReasonCategory reasonCategory, CapacityGroup group,
            int page, int size, @Nullable Integer requestedBucket, @Nullable UUID telemetrySourceId,
            @Nullable UUID openrouterAccountId, LlmConsumerSort sort) {
        Instant now = clock.instant();
        int bucket = bucket(from, to, now, requestedBucket);
        if (error != null && errorTypeMissing) { throw invalid("errorTypeMissing", "errorType과 미기록 필터를 함께 지정할 수 없습니다."); }
        if (!access.observationReader(actor)) { throw denied(); }
        Long requestedOrg = orgId == null ? null : orgs.findByPublicId(orgId).map(Org::getId).orElse(null);
        OrgScope scope = access.queryScope(actor, orgId, requestedOrg);
        Sql scopeSql = keyScope(actor, scope, workspaceId);
        if (openrouterAccountId != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from llm_api_keys k join workspaces w on w.id=k.workspace_id where "
                        + scopeSql.text() + " and not (" + access.bindingKeyRows(actor) + "))",
                Boolean.class, scopeSql.args().toArray()))) { throw denied(); }
        if (workspaceId != null && !Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from llm_api_keys k join workspaces w on w.id=k.workspace_id where " + scopeSql.text() + ")",
                Boolean.class, scopeSql.args().toArray()))) { throw missing(); }
        List<Upstream> upstreams = upstreams(actor, scope);
        String upstream = upstreamId == null ? null : upstreams.stream().filter(item -> item.id().equals(upstreamId))
                .map(Upstream::ref).findFirst().orElseThrow(AdminLlmCapacityService::missing);
        LocalDate swept = jdbc.queryForObject("select max(swept_before) from llm_usage_rollup_state", LocalDate.class);
        Instant retained = swept == null ? null : swept.atStartOfDay(ClockConfig.KST).toInstant();
        List<Object> args = new ArrayList<>();
        args.add(DatabaseTimestamps.ceilingMicros(from)); args.add(DatabaseTimestamps.ceilingMicros(to)); args.addAll(scopeSql.args());
        StringBuilder where = new StringBuilder("e.requested_at>=? and e.requested_at<? and ").append(scopeSql.text());
        if (retained != null && from.isBefore(retained)) { where.append(" and e.requested_at>=?"); args.add(Timestamp.from(retained)); }
        if (openrouterAccountId != null) {
            where.append(" and k.openrouter_account_id=(select id from openrouter_accounts where public_id=?)");
            args.add(openrouterAccountId);
        }
        if (upstream != null) { where.append(" and lower(e.upstream_ref)=?"); args.add(upstream); }
        if (model != null) { bounded(model, "publicModelName"); where.append(" and e.public_model_name=?"); args.add(model); }
        if (error != null) { bounded(error, "errorType"); where.append(" and e.error_type=?"); args.add(error); }
        if (errorTypeMissing) { where.append(" and e.error_type is null"); }
        if (error != null || errorTypeMissing || reasonCategory != null) { where.append(" and (e.status is null or e.status<>'OK')"); }
        String cte = """
                with raw_events as (
                select e.*, k.public_id as key_public_id, k.name as key_name,
                       o.public_id as org_public_id, o.name as org_name,
                       w.public_id as workspace_public_id, w.name as workspace_name,
                """ + access.moneyKeyRows(actor) + " as finance_visible"
                + " from llm_usage_events e left join llm_api_keys k on k.id=e.key_id"
                + " left join orgs o on o.id=k.org_id left join workspaces w on w.id=k.workspace_id where " + where + "),"
                + " classified_events as (select raw_events.*," + CATEGORY + " as reason_category from raw_events),"
                + " events as (select * from classified_events" + (reasonCategory == null ? "" : " where reason_category=?") + ") ";
        if (reasonCategory != null) { args.add(reasonCategory.name()); }
        if (sort == LlmConsumerSort.REPORTED_COST_DESC && (!access.hasMoneySources(actor)
                || Boolean.TRUE.equals(jdbc.queryForObject(cte + "select exists(select 1 from events where not finance_visible)",
                        Boolean.class, args.toArray())))) { throw denied(); }
        CapacityRawHistoryState state = history(from, to, retained);
        boolean available = state != CapacityRawHistoryState.FULLY_SWEPT;
        CapacityCounts summary = jdbc.queryForObject(cte + "select " + COUNTS + " from events",
                (rs, n) -> counts(rs, actor, available), args.toArray());
        List<CapacitySeriesPoint> series = series(cte, args, actor, from, to, bucket, retained);
        List<CapacityReason> reasons = jdbc.query(cte + "select error_type,reason_category"
                + " as category,count(*) as requests,count(distinct key_id) as known_keys from events"
                + " where status is null or status<>'OK' group by error_type,reason_category order by requests desc,error_type nulls first,category",
                (rs, n) -> new CapacityReason(rs.getString("error_type"), CapacityReasonCategory.valueOf(rs.getString("category")),
                        rs.getLong("requests"), rs.getLong("known_keys")), args.toArray());
        CapacityConsumers consumers = consumers(cte, args, actor, group, page, size, now, available, sort);
        Instant received = jdbc.queryForObject("select max(e.received_at) from llm_usage_events e left join llm_api_keys k on k.id=e.key_id"
                + " left join workspaces w on w.id=k.workspace_id where " + scopeSql.text(), Instant.class, scopeSql.args().toArray());
        Instant rollup = jdbc.queryForObject("select max(last_success_at) from llm_usage_rollup_state", Instant.class);
        boolean telemetry = InstitutionLlmAccess.systemRead(actor);
        return new AdminLlmCapacityResponse(now, "Asia/Seoul", from, to, bucket, summary, series, reasons, consumers,
                selectors(scopeSql, upstreams), new CapacityQuality(state, retained, retention.retentionDays(), received,
                        rollup, "UNKNOWN", observations.gatewayStatus(actor, now)), telemetry,
                observationReader.read(actor, from, to, bucket, telemetrySourceId), openrouterAccountId);
    }

    private List<CapacitySeriesPoint> series(String cte, List<Object> args, AuthenticatedUser actor,
            Instant from, Instant to, int bucket, @Nullable Instant retained) {
        List<Object> values = new ArrayList<>(args); values.add(DatabaseTimestamps.ceilingMicros(from)); values.add(bucket);
        Map<Long, CapacityCounts> rows = new HashMap<>();
        jdbc.query(cte + "select floor(extract(epoch from (requested_at-?::timestamptz)) / ?)::bigint as bucket,"
                + COUNTS + " from events group by bucket", rs -> {
                    rows.put(rs.getLong("bucket"), counts(rs, actor, true));
                }, values.toArray());
        List<CapacitySeriesPoint> result = new ArrayList<>();
        for (Instant start = from; start.isBefore(to); start = start.plusSeconds(bucket)) {
            Instant end = start.plusSeconds(bucket).isAfter(to) ? to : start.plusSeconds(bucket);
            CapacityRawHistoryState state = history(start, end, retained);
            CapacityCounts data = state == CapacityRawHistoryState.FULLY_SWEPT ? empty(false, access.hasMoneySources(actor))
                    : rows.getOrDefault(Duration.between(from, start).getSeconds() / bucket, empty(true, access.hasMoneySources(actor)));
            result.add(new CapacitySeriesPoint(start, end, state, data));
        }
        return List.copyOf(result);
    }

    private CapacityConsumers consumers(String cte, List<Object> args, AuthenticatedUser actor,
            CapacityGroup group, int page, int size, Instant now, boolean available, LlmConsumerSort sort) {
        String dimensions = switch (group) {
            case ORG -> "org_public_id,org_name";
            case WORKSPACE -> "workspace_public_id,workspace_name";
            case KEY -> "org_public_id,org_name,workspace_public_id,workspace_name,key_public_id,key_name";
        };
        String missing = switch (group) {
            case ORG -> "null::uuid as workspace_public_id,null::text as workspace_name,null::uuid as key_public_id,null::text as key_name,";
            case WORKSPACE -> "null::uuid as org_public_id,null::text as org_name,null::uuid as key_public_id,null::text as key_name,";
            case KEY -> "";
        };
        Long total = jdbc.queryForObject(cte + "select count(*) from (select 1 from events group by " + dimensions + ") grouped", Long.class, args.toArray());
        List<Object> values = new ArrayList<>(args); values.add(size); values.add((long) page * size);
        String tie = switch (group) { case ORG -> "org_public_id"; case WORKSPACE -> "workspace_public_id"; case KEY -> "key_public_id"; };
        String ordering = switch (sort) {
            case REQUESTS_DESC -> "requests desc";
            case TOKENS_DESC -> "(sum(input_tokens)+sum(output_tokens)) desc";
            case REPORTED_COST_DESC -> "sum(cost_usd) desc nulls last";
        };
        List<CapacityConsumer> items = jdbc.query(cte + "select " + dimensions + "," + missing + COUNTS
                + " from events group by " + dimensions + " order by " + ordering + "," + tie + " nulls last limit ? offset ?",
                (rs, n) -> {
                    UUID key = rs.getObject("key_public_id", UUID.class);
                    return new CapacityConsumer(rs.getObject("org_public_id", UUID.class), rs.getString("org_name"),
                            rs.getObject("workspace_public_id", UUID.class), rs.getString("workspace_name"), key, rs.getString("key_name"),
                            counts(rs, actor, available), null);
                }, values.toArray());
        Map<UUID, CapacityCurrentLimits> limits = currentLimits(items.stream().map(CapacityConsumer::keyId)
                .filter(java.util.Objects::nonNull).toList(), now);
        items = items.stream().map(item -> new CapacityConsumer(item.orgId(), item.orgName(), item.workspaceId(),
                item.workspaceName(), item.keyId(), item.keyName(), item.counts(), limits.get(item.keyId()))).toList();
        long count = total == null ? 0 : total;
        return new CapacityConsumers(group, items, page, size, count,
                (int) Math.min(Integer.MAX_VALUE, (count + size - 1) / size), sort);
    }

    private Map<UUID, CapacityCurrentLimits> currentLimits(List<UUID> keys, Instant now) {
        Map<UUID, CapacityCurrentLimits> result = new HashMap<>();
        if (keys.isEmpty()) { return result; }
        String ids = "{" + keys.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",")) + "}";
        jdbc.query("select public_id,rpm,tpm,concurrency,daily_tokens,quota_exhausted from llm_api_keys where public_id=any(?::uuid[])",
                rs -> { result.put(rs.getObject("public_id", UUID.class), new CapacityCurrentLimits(now,
                        rs.getObject("rpm", Integer.class), rs.getObject("tpm", Integer.class),
                        rs.getObject("concurrency", Integer.class), rs.getObject("daily_tokens", Long.class),
                        rs.getBoolean("quota_exhausted"), false)); }, ids);
        return result;
    }

    private CapacitySelectors selectors(Sql scope, List<Upstream> upstreams) {
        CapacityReferenceCollection institutions = references("select distinct o.public_id as id,o.name from llm_api_keys k"
                + " join workspaces w on w.id=k.workspace_id join orgs o on o.id=k.org_id where " + scope.text(), scope.args());
        CapacityReferenceCollection workspaces = references("select distinct w.public_id as id,w.name from llm_api_keys k"
                + " join workspaces w on w.id=k.workspace_id where " + scope.text(), scope.args());
        return new CapacitySelectors(institutions, workspaces, new CapacityReferenceCollection(
                upstreams.stream().limit(REFERENCE_LIMIT).map(item -> new CapacityReference(item.id(), item.name())).toList(), upstreams.size(), upstreams.size() > REFERENCE_LIMIT));
    }

    private CapacityReferenceCollection references(String sql, List<Object> args) {
        Long total = jdbc.queryForObject("select count(*) from (" + sql + ") refs", Long.class, args.toArray());
        List<CapacityReference> items = jdbc.query(sql + " order by name,id limit " + REFERENCE_LIMIT,
                (rs, n) -> new CapacityReference(rs.getObject("id", UUID.class), rs.getString("name")), args.toArray());
        return new CapacityReferenceCollection(items, total == null ? 0 : total, total != null && total > items.size());
    }

    private List<Upstream> upstreams(AuthenticatedUser actor, OrgScope scope) {
        Set<String> refs = observations.allowedRefs(actor, scope);
        return jdbc.query("select public_id,display_name,lower(ref) as ref from llm_upstreams order by display_name,public_id",
                (rs, n) -> new Upstream(rs.getObject("public_id", UUID.class), rs.getString("display_name"), rs.getString("ref")))
                .stream().filter(item -> refs == null || refs.contains(item.ref())).toList();
    }

    private Sql keyScope(AuthenticatedUser actor, OrgScope scope, @Nullable UUID workspaceId) {
        StringBuilder text = new StringBuilder("(").append(access.observationKeyRows(actor)).append(")");
        List<Object> args = new ArrayList<>();
        if (!scope.isUnrestricted()) { text.append(" and ").append(scope.guard("k.org_id")); args.add(scope.arrayParam()); args.add(scope.arrayParam()); }
        if (workspaceId != null) { text.append(" and w.public_id=?"); args.add(workspaceId); }
        return new Sql(text.toString(), args);
    }

    private CapacityCounts counts(ResultSet rs, AuthenticatedUser actor, boolean available) throws SQLException {
        boolean finance = access.hasMoneySources(actor) && rs.getBoolean("finance_visible");
        if (!available) { return empty(false, finance); }
        return new CapacityCounts(rs.getLong("requests"), rs.getLong("succeeded"), rs.getLong("rate_limited"), rs.getLong("failed"),
                rs.getLong("canceled"), rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getLong("estimated_requests"),
                rs.getLong("token_axis_requests"), rs.getLong("credit_axis_requests"), rs.getLong("unknown_axis_requests"), rs.getLong("unknown_key_requests"),
                rs.getLong("unknown_model_requests"), rs.getLong("unknown_final_upstream_requests"), finance ? rs.getBigDecimal("cost_usd") : null,
                finance ? rs.getLong("priced_requests") : null, finance);
    }

    private static CapacityCounts empty(boolean available, boolean finance) {
        Long count = available ? 0L : null;
        return new CapacityCounts(count, count, count, count, count, count, count, count, count, count, count, count, count, count,
                null, available && finance ? 0L : null, finance);
    }

    private static CapacityRawHistoryState history(Instant from, Instant to, @Nullable Instant retained) {
        if (retained == null) { return CapacityRawHistoryState.NOT_SWEPT; }
        if (!to.isAfter(retained)) { return CapacityRawHistoryState.FULLY_SWEPT; }
        return from.isBefore(retained) ? CapacityRawHistoryState.PARTIALLY_SWEPT : CapacityRawHistoryState.WITHIN_RETAINED_RANGE;
    }

    private static int bucket(Instant from, Instant to, Instant now, @Nullable Integer requested) {
        Duration duration = Duration.between(from, to);
        if (!from.isBefore(to) || to.isAfter(now) || duration.compareTo(Duration.ofDays(90)) > 0) {
            throw invalid("from", "조회 기간은 미래가 아닌 90일 이내의 시작 포함·종료 제외 구간이어야 합니다.");
        }
        long seconds = Math.max(1, duration.getSeconds());
        int selected = requested != null ? requested : seconds <= 21600 ? 60 : seconds <= 86400 ? 300
                : seconds <= 604800 ? 3600 : seconds <= 2592000 ? 21600 : 86400;
        if (selected < 60 || selected > 86400 || (duration.toNanos() - 1) / (selected * 1_000_000_000L) + 1 > 1440) {
            throw invalid("bucketSeconds", "버킷은 60~86400초이며 조회 구간의 버킷 수는 1440개 이하여야 합니다.");
        }
        return selected;
    }

    private static void bounded(String value, String field) {
        if (value.isBlank() || value.length() > 256) { throw invalid(field, "필터는 1~256자의 정확한 값이어야 합니다."); }
    }

    private static ApiException invalid(String field, String message) { return ApiException.validationFailed(List.of(new FieldValidationError(field, message))); }
    private static ApiException denied() { return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED, "접근 권한이 없습니다", "기관 LLM 관측 업무가 필요합니다."); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, "리소스를 찾을 수 없습니다", "해당 관측 대상을 찾을 수 없습니다."); }
    private record Sql(String text, List<Object> args) { }
    private record Upstream(UUID id, String name, String ref) { }
}
