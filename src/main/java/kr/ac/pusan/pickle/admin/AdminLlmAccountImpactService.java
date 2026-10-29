package kr.ac.pusan.pickle.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ActorAccessImpacts;
import kr.ac.pusan.pickle.admin.bulk.BulkFingerprints;
import kr.ac.pusan.pickle.admin.dto.LlmAccountImpact;
import kr.ac.pusan.pickle.admin.dto.LlmAccountImpactAmounts;
import kr.ac.pusan.pickle.admin.dto.LlmAccountImpactProjection;
import kr.ac.pusan.pickle.admin.dto.LlmAccountImpactProjection.LlmAccountImpactState;
import kr.ac.pusan.pickle.admin.dto.LlmAccountImpactQuality;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmKeyLimitRules;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccount;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAllocationQuery;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAllocationQuery.Allocation;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAllocationQuery.ProjectedAllocation;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCreditsFreshness;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Information snapshots share the server's commitment arithmetic, never write admission. */
@Service
public class AdminLlmAccountImpactService {
    private final OpenRouterAllocationQuery allocation;
    private final InstitutionLlmAccess access;
    private final InstitutionLlmQueryAccess sources;
    private final InstitutionAccountAccess accountAccess;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AdminLlmAccountImpactService(OpenRouterAllocationQuery allocation, InstitutionLlmAccess access,
            InstitutionLlmQueryAccess sources, InstitutionAccountAccess accountAccess,
            JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.allocation = allocation; this.access = access; this.sources = sources; this.accountAccess = accountAccess;
        this.jdbc = jdbc; this.mapper = mapper; this.clock = clock;
    }

    public record Plan(LlmApiKey key, LlmKeyLimitRules.Normalized next,
            @Nullable OpenRouterAccount account, boolean changed) { }

    public LlmAccountImpactProjection evaluate(AuthenticatedUser actor, List<Plan> proposed) {
        AuthenticatedUser current = access.currentForRead(actor);
        Instant now = clock.instant();
        List<Plan> plans = proposed.stream().filter(plan -> plan.account() != null).toList();
        if (plans.isEmpty()) { return LlmAccountImpactProjection.notApplicable(now); }
        List<LlmApiKey> keys = plans.stream().map(Plan::key).toList();
        if (!targetRead(current, keys) || plans.stream().anyMatch(plan -> !accountAccess.work(current,
                InstitutionWork.INSTITUTION_ACCOUNT_FINANCE_READ, Objects.requireNonNull(plan.account()).getOrgId()))) {
            return LlmAccountImpactProjection.notProvided(now);
        }
        List<Long> accountIds = plans.stream().map(plan -> Objects.requireNonNull(plan.account()).getId()).distinct().toList();
        var basis = allocation.snapshot(accountIds, keys.stream().map(LlmApiKey::getId).toList());
        for (Plan plan : plans) {
            var target = basis.keys().get(plan.key().getId());
            if (target == null || !target.matchesConfiguration(plan.key())) {
                return LlmAccountImpactProjection.notProvided(basis.observedAt());
            }
        }
        List<LlmAccountImpact> result = new ArrayList<>();
        for (Long accountId : accountIds.stream().sorted().toList()) {
            var observation = basis.accounts().get(accountId);
            if (observation == null) { return LlmAccountImpactProjection.notProvided(basis.observedAt()); }
            List<Plan> group = plans.stream().filter(plan -> Objects.equals(
                    Objects.requireNonNull(plan.account()).getId(), accountId)).toList();
            var changes = group.stream().map(plan -> basis.keys().get(plan.key().getId()).replacement(accountId,
                    plan.next().creditLimit(), plan.next().creditLimitReset())).toList();
            Allocation before = basis.allocations().get(accountId);
            ProjectedAllocation after = OpenRouterAllocationQuery.project(before, changes);
            Clock atBasis = Clock.fixed(basis.observedAt(), ZoneOffset.UTC);
            boolean windowed = before.committedDaily().signum() > 0 || before.committedWeekly().signum() > 0
                    || before.committedMonthly().signum() > 0 || after.committedDaily().signum() > 0
                    || after.committedWeekly().signum() > 0 || after.committedMonthly().signum() > 0;
            LlmAccountImpactQuality quality = new LlmAccountImpactQuality("SERVER_CACHED_ALLOCATION", basis.observedAt(),
                    OpenRouterCreditsFreshness.of(observation.creditsLastSuccessAt(), Duration.ofMinutes(30), atBasis),
                    OpenRouterCreditsFreshness.of(observation.keysLastSuccessAt(), Duration.ofMinutes(30), atBasis),
                    observation.creditsLastSuccessAt(), observation.creditsLastAttemptAt(),
                    observation.keysLastSuccessAt(), observation.keysLastAttemptAt(), observation.keysError(),
                    observation.balance() == null, before.usageUnreportedKeyCount(), after.usageUnreportedKeyCount(),
                    before.awaitingProvisionKeyCount(), after.awaitingProvisionKeyCount(), after.changedPeriodKeys(),
                    after.changedPeriodKeys() > 0, windowed, windowed);
            result.add(new LlmAccountImpact(observation.publicId(), observation.name(),
                    (int) group.stream().filter(Plan::changed).count(), amounts(before), amounts(after),
                    observation.balance(), observation.creditsObservedAt(), observation.creditsError(),
                    observation.balance() == null || after.remainingCommitment() == null ? null
                            : after.remainingCommitment().compareTo(observation.balance()) > 0, quality));
        }
        JsonNode visibleBasis = mapper.valueToTree(result);
        for (JsonNode row : visibleBasis) { ((ObjectNode) row.get("quality")).remove("observedAt"); }
        String fingerprint = BulkFingerprints.of(mapper, Map.of("authorityContext", ActorAccessImpacts.authorityContext(current),
                "source", "SERVER_CACHED_ALLOCATION", "accounts", visibleBasis));
        return new LlmAccountImpactProjection(LlmAccountImpactState.AVAILABLE, basis.observedAt(), fingerprint, List.copyOf(result));
    }

    /** Old readable snapshots stay snapshots; only their current field admission is recomputed. */
    public @Nullable LlmAccountImpactProjection remask(AuthenticatedUser actor,
            @Nullable LlmAccountImpactProjection saved, List<LlmApiKey> keys) {
        if (saved == null || saved.state() != LlmAccountImpactState.AVAILABLE) { return saved; }
        AuthenticatedUser current = access.currentForRead(actor);
        if (saved.accounts() == null || !targetRead(current, keys)) {
            return LlmAccountImpactProjection.notProvided(saved.observedAt());
        }
        String ids = "{" + saved.accounts().stream().map(item -> item.accountId().toString())
                .collect(Collectors.joining(",")) + "}";
        List<Long> institutions = jdbc.query("select org_id from openrouter_accounts where public_id=any(?::uuid[])",
                (rs, n) -> rs.getLong(1), ids);
        if (institutions.size() != saved.accounts().size() || institutions.stream().anyMatch(id -> !accountAccess.work(current,
                InstitutionWork.INSTITUTION_ACCOUNT_FINANCE_READ, id))) {
            return LlmAccountImpactProjection.notProvided(saved.observedAt());
        }
        return saved;
    }

    private boolean targetRead(AuthenticatedUser actor, List<LlmApiKey> keys) {
        if (keys.isEmpty()) { return false; }
        String ids = "{" + keys.stream().map(key -> Long.toString(key.getId())).distinct().collect(Collectors.joining(",")) + "}";
        boolean[] complete = {false};
        jdbc.query("select count(*) as matched,coalesce(bool_and(" + sources.moneyKeyRows(actor)
                + "),false) as complete from llm_api_keys k where k.id=any(?::bigint[])", rs -> {
                    complete[0] = rs.getLong("matched") == keys.stream().map(LlmApiKey::getId).distinct().count()
                            && rs.getBoolean("complete");
                }, ids);
        return complete[0];
    }

    private static LlmAccountImpactAmounts amounts(Allocation value) {
        return new LlmAccountImpactAmounts(value.committedCreditLimit(), value.committedTotalCap(), value.committedDaily(),
                value.committedWeekly(), value.committedMonthly(), value.committedKeyCount(), value.remainingCommitment(),
                value.awaitingProvisionKeyCount(), value.usageUnreportedKeyCount());
    }
    private static LlmAccountImpactAmounts amounts(ProjectedAllocation value) {
        return new LlmAccountImpactAmounts(value.committedCreditLimit(), value.committedTotalCap(), value.committedDaily(),
                value.committedWeekly(), value.committedMonthly(), value.committedKeyCount(), value.remainingCommitment(),
                value.awaitingProvisionKeyCount(), value.usageUnreportedKeyCount());
    }
}
