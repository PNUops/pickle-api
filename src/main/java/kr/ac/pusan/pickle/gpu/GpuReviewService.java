package kr.ac.pusan.pickle.gpu;

import kr.ac.pusan.pickle.common.error.ErrorCodes;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.gpu.dto.GpuReclaimReviewView;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class GpuReviewService {
    private final GpuStore store;
    private final GpuNotices notices;
    private final GpuMutationService mutations;
    private final SettingsService settings;
    private final ObjectMapper json;
    private final Clock clock;
    private final AuditService audit;
    public GpuReviewService(GpuStore store, GpuNotices notices, GpuMutationService mutations, SettingsService settings,
            ObjectMapper json, Clock clock, AuditService audit) {
        this.store = store; this.notices = notices; this.mutations = mutations; this.settings = settings;
        this.json = json; this.clock = clock; this.audit = audit;
    }
    @Transactional
    public void open(long allocationId, String reason, Map<String, Object> evidence) {
        GpuAllocation a = store.lock(allocationId);
        if (a.status() != GpuAllocationStatus.ALLOCATED || !a.leaseEndsAt().isAfter(clock.instant())) { return; }
        if ("UNATTACHED".equals(reason) && (a.connectionStatus() != GpuConnectionStatus.NONE
                || a.unattachedSince() == null || !a.unattachedSince().toString().equals(evidence.get("unattachedSince")))) { return; }
        if ("LOW_UTILIZATION".equals(reason) && (a.connectionStatus() != GpuConnectionStatus.ATTACHED
                || a.attachedAt() == null || !a.attachedAt().toString().equals(evidence.get("attachedAt")))) { return; }
        boolean snoozed = Boolean.TRUE.equals(store.jdbc().queryForObject("select exists(select 1 from gpu_reclaim_reviews where allocation_id = ? and snoozed_until > ?)",
                Boolean.class, allocationId, Timestamp.from(clock.instant())));
        if (snoozed) { return; }
        List<UUID> ids = store.jdbc().query("""
                insert into gpu_reclaim_reviews(allocation_id, reason, evidence) values (?, ?, ?::jsonb)
                on conflict (allocation_id) where decision is null do nothing returning public_id
                """, (rs, row) -> rs.getObject(1, UUID.class), allocationId, reason, json.writeValueAsString(evidence));
        if (!ids.isEmpty()) {
            notices.administrators("GPU '" + a.name() + "'의 " + ("UNATTACHED".equals(reason) ? "미연결" : "저사용") + " 상태를 검토해 주세요. 자동으로 회수하지 않습니다.", "gpu.review:" + ids.getFirst());
        }
    }
    @Transactional(readOnly = true)
    public PageResponse<GpuReclaimReviewView> list(AuthenticatedUser actor, UUID orgId, int page, int size) {
        GpuMutationService.requireSystemOperator(actor);
        var all = store.jdbc().query("select r.*, a.public_id as allocation_public_id, a.name as allocation_name from gpu_reclaim_reviews r join gpu_allocations a on a.id = r.allocation_id join orgs o on o.id = a.org_id where (?::uuid is null or o.public_id = ?::uuid) order by r.created_at desc, r.id desc", (rs, row) -> {
            String decision = rs.getString("decision");
            return new GpuReclaimReviewView(rs.getObject("public_id", UUID.class), rs.getObject("allocation_public_id", UUID.class),
                    rs.getString("allocation_name"), rs.getString("reason"), json.readValue(rs.getString("evidence"), new TypeReference<Map<String, Object>>() {}),
                    decision == null ? null : GpuReviewDecision.valueOf(decision), rs.getString("decision_reason"), GpuStore.instant(rs, "created_at"), GpuStore.instant(rs, "decided_at"));
        }, orgId, orgId);
        return new PageResponse<>(all.stream().skip((long) page * size).limit(size).toList(), page, size, all.size(), (all.size() + size - 1) / size);
    }
    @Transactional
    public void decide(AuthenticatedUser actor, UUID id, GpuReviewDecision decision, String reason, String ip) {
        GpuMutationService.requireSystemOperator(actor);
        var ids = store.jdbc().queryForList("select allocation_id from gpu_reclaim_reviews where public_id = ?", Long.class, id);
        if (ids.isEmpty()) { throw GpuErrors.notFound(); }
        store.lock(ids.getFirst());
        var rows = store.jdbc().query("select allocation_id, decision from gpu_reclaim_reviews where public_id = ? for update", (rs, row) -> Map.entry(rs.getLong("allocation_id"), rs.getString("decision") == null ? "" : rs.getString("decision")), id);
        if (rows.isEmpty()) { throw GpuErrors.notFound(); }
        if (!rows.getFirst().getValue().isEmpty()) { throw GpuErrors.conflict(ErrorCodes.GPU_REVIEW_ALREADY_DECIDED, "이미 처리한 검토입니다."); }
        long allocation = rows.getFirst().getKey();
        Timestamp snooze = decision == GpuReviewDecision.KEEP ? Timestamp.from(clock.instant().plus(Duration.ofHours(settings.requiredValid(SettingsService.GPU_LOW_UTIL_SNOOZE_HOURS).asInt()))) : null;
        if (decision == GpuReviewDecision.RECLAIM) { mutations.reclaim(actor, store.allocation(allocation).orElseThrow().publicId(), reason, ip); }
        store.jdbc().update("update gpu_reclaim_reviews set decision = ?, decision_reason = ?, decided_by = ?, decided_at = ?, snoozed_until = ? where public_id = ?", decision.name(), reason, actor.id(), Timestamp.from(clock.instant()), snooze, id);
        audit.recordAfterCommit(actor.id(), actor.role().name(), "gpu.review.decide", "gpu_reclaim_review", id, Map.of("decision", decision.name(), "reason", reason), ip);
    }
}
