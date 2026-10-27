package kr.ac.pusan.pickle.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kr.ac.pusan.pickle.admin.dto.LlmKeyCohort;
import kr.ac.pusan.pickle.admin.dto.LlmKeySort;
import kr.ac.pusan.pickle.admin.dto.LlmAccountKeyNavigation;
import kr.ac.pusan.pickle.admin.dto.LlmNavigationCount;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.llm.CreditModelPatterns;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.llm.PassthroughEndpoints;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCatalogueRepository;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccount;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.support.SqlArrayValue;

/** One readable key relation feeds cohort membership, total, order and page. */
@Service
public class AdminLlmKeyExplorationQuery {
    private final JdbcTemplate jdbc;
    private final InstitutionLlmQueryAccess sources;
    private final InstitutionAccountAccess accounts;
    private final OpenRouterCatalogueRepository catalogue;
    private final Clock clock;
    private final InstitutionLlmAccess access;

    public AdminLlmKeyExplorationQuery(JdbcTemplate jdbc, InstitutionLlmQueryAccess sources,
            InstitutionAccountAccess accounts, OpenRouterCatalogueRepository catalogue, Clock clock,
            InstitutionLlmAccess access) {
        this.jdbc = jdbc; this.sources = sources; this.accounts = accounts; this.catalogue = catalogue; this.clock = clock;
        this.access = access;
    }

    public record Filters(OrgScope scope, @Nullable Long workspaceId, @Nullable Long requestId,
            @Nullable Long accountId, @Nullable LlmApiKeyStatus status, @Nullable String query) { }
    public record Selection(List<Long> ids, long total, Map<Long, Long> todayTokens,
            Instant observedAt, java.time.LocalDate tokenDay) { }
    private record Sql(String where, List<Object> args) { }

    public Map<Long, LlmAccountKeyNavigation> navigation(AuthenticatedUser actor, List<OpenRouterAccount> requested) {
        AuthenticatedUser current = access.currentForRead(actor);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Map<Long, LlmAccountKeyNavigation> result = new HashMap<>();
        if (requested.isEmpty()) { return result; }
        boolean reader = access.collectionReader(current);
        for (OpenRouterAccount account : requested) {
            result.put(account.getId(), new LlmAccountKeyNavigation(now, "CURRENT_READABLE_KEYS",
                    reader ? LlmNavigationCount.available(0) : LlmNavigationCount.notProvided(),
                    LlmNavigationCount.notProvided(), LlmNavigationCount.notProvided(), LlmNavigationCount.notProvided()));
        }
        if (!reader) { return result; }
        Map<Long, long[]> counts = new HashMap<>();
        String ids = "{" + requested.stream().map(account -> Long.toString(account.getId()))
                .collect(java.util.stream.Collectors.joining(",")) + "}";
        jdbc.query("with visible as (select k.*, (k.credit_limit>0 and k.status in ('PENDING','ACTIVE','SUSPENDED')"
                + " and (k.expires_at is null or k.expires_at>?)) as live_commitment,"
                + sources.moneyKeyRows(current) + " as money_visible from llm_api_keys k where "
                + sources.selectorKeyRows(current) + " and k.openrouter_account_id=any(?::bigint[])) "
                + "select openrouter_account_id,count(*) as linked,count(*) filter(where live_commitment) as committed,"
                + "count(*) filter(where live_commitment and openrouter_key_hash is null) as awaiting,"
                + "count(*) filter(where live_commitment and openrouter_key_hash is not null and openrouter_usage is null) as unreported,"
                + "bool_and(money_visible) as complete_money from visible group by openrouter_account_id", rs -> {
                    counts.put(rs.getLong("openrouter_account_id"), new long[] {rs.getLong("linked"), rs.getLong("committed"),
                            rs.getLong("awaiting"), rs.getLong("unreported"), rs.getBoolean("complete_money") ? 1 : 0});
                }, Timestamp.from(now), ids);
        for (OpenRouterAccount account : requested) {
            long[] row = counts.getOrDefault(account.getId(), new long[] {0, 0, 0, 0, 1});
            boolean finance = sources.hasMoneySources(current) && row[4] == 1
                    && accounts.work(current, InstitutionWork.INSTITUTION_ACCOUNT_FINANCE_READ, account.getOrgId());
            result.put(account.getId(), new LlmAccountKeyNavigation(now, "CURRENT_READABLE_KEYS",
                    LlmNavigationCount.available(row[0]), finance ? LlmNavigationCount.available(row[1]) : LlmNavigationCount.notProvided(),
                    finance ? LlmNavigationCount.available(row[2]) : LlmNavigationCount.notProvided(),
                    finance ? LlmNavigationCount.available(row[3]) : LlmNavigationCount.notProvided()));
        }
        return Map.copyOf(result);
    }

    public Selection page(AuthenticatedUser actor, Filters filters, LlmKeyCohort cohort, LlmKeySort sort,
            @Nullable String policyModel, String policyFeature, int page, int size) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        java.time.LocalDate today = now.atZone(ClockConfig.KST).toLocalDate();
        Sql base = base(actor, filters, now);
        if (cohort != LlmKeyCohort.ALL || sort == LlmKeySort.CREDIT_LIMIT_DESC || sort == LlmKeySort.CREDIT_REMAINING_ASC) {
            if (!sources.hasMoneySources(actor) || missing(base, sources.moneyKeyRows(actor))
                    || !accountFinance(actor, base)) { throw denied(); }
        }
        if (sort == LlmKeySort.TOKEN_USAGE_DESC && missing(base, sources.observationFieldKeyRows(actor))) { throw denied(); }
        StringBuilder where = new StringBuilder(base.where());
        List<Object> args = new ArrayList<>(base.args());
        if (cohort != LlmKeyCohort.ALL) {
            where.append(" and k.credit_limit>0 and k.status in ('PENDING','ACTIVE','SUSPENDED')")
                    .append(" and (k.expires_at is null or k.expires_at>?)");
            args.add(Timestamp.from(now));
            if (cohort == LlmKeyCohort.AWAITING_PROVISION) { where.append(" and k.openrouter_key_hash is null"); }
            if (cohort == LlmKeyCohort.USAGE_UNREPORTED) {
                where.append(" and k.openrouter_key_hash is not null and k.openrouter_usage is null");
            }
        }
        if (policyModel != null) {
            String model = policyModel.strip();
            if (model.isEmpty() || model.length() > 256 || model.codePoints().anyMatch(Character::isISOControl)) {
                throw invalid("policyModel", "공개 모델 이름을 확인해 주세요.");
            }
            String feature = policyFeature.strip().toLowerCase(Locale.ROOT);
            List<FieldValidationError> errors = new ArrayList<>();
            PassthroughEndpoints.normalize(List.of(feature), "policyFeature", errors);
            if (!errors.isEmpty()) { throw ApiException.validationFailed(errors); }
            boolean token = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from llm_models "
                    + "where public_name=? and enabled and visibility='PUBLIC' and budget_axis='TOKEN')", Boolean.class, model));
            if (!token && catalogue.listed().stream().noneMatch(row -> row.modelId().equalsIgnoreCase(model))) {
                throw invalid("policyModel", "현재 cache에서 확인되는 공개 모델을 선택해 주세요.");
            }
            boolean financial = !token || !PassthroughEndpoints.CHAT.equals(feature);
            if (missing(base, sources.policyKeyRows(actor, financial))) { throw denied(); }
            where.append(" and jsonb_exists(k.passthrough_endpoints,?)"); args.add(feature);
            if (!token) {
                if (CreditModelPatterns.isRouter(model)) {
                    where.append(" and jsonb_array_length(k.credit_allowed_models)=0 and jsonb_array_length(k.credit_denied_models)=0");
                } else {
                    where.append(" and (jsonb_array_length(k.credit_allowed_models)=0 or jsonb_exists_any(k.credit_allowed_models,?::text[]))")
                            .append(" and not jsonb_exists_any(k.credit_denied_models,?::text[])");
                    args.add(new SqlArrayValue("text", CreditModelPatterns.matchingPatternsFor(model, false).toArray()));
                    args.add(new SqlArrayValue("text", CreditModelPatterns.matchingPatternsFor(model, true).toArray()));
                }
            }
        }
        String cte = "with visible_keys as (select k.* from llm_api_keys k where " + where + "), today_usage as ("
                + "select e.key_id,sum(e.input_tokens::bigint+e.output_tokens::bigint) as tokens from llm_usage_events e "
                + "join visible_keys k on k.id=e.key_id where e.requested_at>=?::date::timestamp at time zone 'Asia/Seoul' "
                + "and e.requested_at<(?::date+1)::timestamp at time zone 'Asia/Seoul' and "
                + "(e.budget_axis='TOKEN' or (e.budget_axis is null and exists(select 1 from llm_models m "
                + "where m.public_name=e.public_model_name and m.budget_axis='TOKEN'))) group by e.key_id) ";
        args.add(today); args.add(today);
        String order = switch (sort) {
            case NEWEST -> "k.id desc";
            case NAME_ASC -> "lower(k.name),k.id desc";
            case TOKEN_USAGE_DESC -> "coalesce(t.tokens,0) desc,k.id desc";
            case CREDIT_LIMIT_DESC -> "k.credit_limit desc nulls last,k.id desc";
            case CREDIT_REMAINING_ASC -> "k.openrouter_limit_remaining asc nulls last,k.id desc";
        };
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add((long) page * size);
        List<Long> ids = new ArrayList<>(); Map<Long, Long> usage = new HashMap<>(); long[] total = {0};
        jdbc.query(cte + "select k.id,coalesce(t.tokens,0) as today_tokens,count(*) over() as total "
                + "from visible_keys k left join today_usage t on t.key_id=k.id order by " + order + " limit ? offset ?", rs -> {
                    long id = rs.getLong("id"); ids.add(id); usage.put(id, rs.getLong("today_tokens")); total[0] = rs.getLong("total");
                }, pageArgs.toArray());
        if (ids.isEmpty()) {
            Long count = jdbc.queryForObject(cte + "select count(*) from visible_keys", Long.class, args.toArray());
            total[0] = count == null ? 0 : count;
        }
        return new Selection(List.copyOf(ids), total[0], Map.copyOf(usage), now, today);
    }

    private Sql base(AuthenticatedUser actor, Filters filters, Instant now) {
        StringBuilder where = new StringBuilder("(" + sources.selectorKeyRows(actor) + ")");
        List<Object> args = new ArrayList<>();
        if (!filters.scope().isUnrestricted()) {
            where.append(" and ").append(filters.scope().guard("k.org_id"));
            args.add(filters.scope().arrayParam()); args.add(filters.scope().arrayParam());
        }
        if (filters.workspaceId() != null) { where.append(" and k.workspace_id=?"); args.add(filters.workspaceId()); }
        if (filters.requestId() != null) { where.append(" and k.request_id=?"); args.add(filters.requestId()); }
        if (filters.accountId() != null) { where.append(" and k.openrouter_account_id=?"); args.add(filters.accountId()); }
        if (filters.status() == LlmApiKeyStatus.EXPIRED) {
            where.append(" and (k.status='EXPIRED' or (k.status<>'REVOKED' and k.expires_at<=?))"); args.add(Timestamp.from(now));
        } else if (filters.status() == LlmApiKeyStatus.REVOKED) { where.append(" and k.status='REVOKED'"); }
        else if (filters.status() != null) {
            where.append(" and k.status=?::llm_api_key_status and (k.expires_at is null or k.expires_at>?)");
            args.add(filters.status().name()); args.add(Timestamp.from(now));
        }
        if (filters.query() != null && !filters.query().isBlank()) {
            String pattern = "%" + filters.query().strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                    .replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" and (lower(k.name) like ? escape E'\\\\' or ((")
                    .append(sources.metadataKeyRows(actor)).append(") and lower(k.purpose) like ? escape E'\\\\'))");
            args.add(pattern); args.add(pattern);
        }
        return new Sql(where.toString(), List.copyOf(args));
    }

    private boolean missing(Sql base, String allowedRows) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from llm_api_keys k where "
                + base.where() + " and not (" + allowedRows + "))", Boolean.class, base.args().toArray()));
    }
    private boolean accountFinance(AuthenticatedUser actor, Sql base) {
        if (InstitutionLlmAccess.systemRead(actor)) { return true; }
        List<Long> institutions = jdbc.query("select distinct k.org_id from llm_api_keys k where " + base.where(),
                (rs, n) -> rs.getObject(1, Long.class), base.args().toArray());
        return institutions.stream().allMatch(id -> id != null
                && accounts.work(actor, InstitutionWork.INSTITUTION_ACCOUNT_FINANCE_READ, id));
    }
    private static ApiException denied() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                "접근 권한이 없습니다", "현재 조회 집합의 해당 필드를 읽을 수 없어 이 탐색 조건을 제공하지 않습니다.");
    }
    private static ApiException invalid(String field, String message) {
        return ApiException.validationFailed(List.of(new FieldValidationError(field, message)));
    }
}
