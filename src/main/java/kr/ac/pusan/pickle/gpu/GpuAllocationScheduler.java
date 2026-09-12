package kr.ac.pusan.pickle.gpu;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reserving a card starts the lease even when its holder has no VM. */
@Component
public class GpuAllocationScheduler {
    private final GpuStore store;
    private final GpuNotices notices;
    private final Clock clock;
    public GpuAllocationScheduler(GpuStore store, GpuNotices notices, Clock clock) {
        this.store = store; this.notices = notices; this.clock = clock;
    }
    @Recurring(id = "gpu-allocation-scheduler", interval = "PT1M")
    @Job(name = "gpu-allocation-scheduler", retries = 0)
    @Transactional
    public void allocate() {
        if (!Boolean.TRUE.equals(store.jdbc().queryForObject("select pg_try_advisory_xact_lock(734891206)", Boolean.class))) { return; }
        Instant now = clock.instant();
        LocalDate today = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        store.jdbc().update("update gpu_allocations set status = 'CANCELED', release_reason = 'GRANT_ENDED', released_at = ?, updated_at = ? where status = 'QUEUED' and granted_end_date < ?",
                Timestamp.from(now), Timestamp.from(now), today);
        List<Long> free = store.jdbc().queryForList("""
                select g.id from gpus g join nodes n on n.id = g.node_id
                 where g.status = 'ACTIVE' and n.status = 'ACTIVE'
                 and not exists(select 1 from gpu_allocations a where a.gpu_id = g.id and a.status in ('ALLOCATED','RELEASING'))
                 order by g.id for update of g
                """, Long.class);
        for (Long gpuId : free) {
            var candidates = store.jdbc().query("""
                    select * from gpu_allocations where status = 'QUEUED'
                     and (preferred_gpu_id is null or preferred_gpu_id = ?)
                     and (granted_start_date is null or granted_start_date <= ?)
                     order by priority desc, queued_at, id limit 1 for update
                    """, GpuStore::allocation, gpuId, today);
            if (candidates.isEmpty()) { continue; }
            GpuAllocation a = candidates.getFirst();
            Instant until = now.plus(Duration.ofHours(a.grantedLeaseHours()));
            if (a.grantedEndDate() != null) {
                Instant approvedEnd = a.grantedEndDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
                if (approvedEnd.isBefore(until)) { until = approvedEnd; }
            }
            store.jdbc().update("""
                    update gpu_allocations set gpu_id = ?, status = 'ALLOCATED', allocated_at = ?, lease_ends_at = ?,
                        unattached_since = ?, updated_at = ? where id = ? and status = 'QUEUED'
                    """, gpuId, Timestamp.from(now), Timestamp.from(until), Timestamp.from(now), Timestamp.from(now), a.id());
            notices.holders(a, "GPU 할당 완료", "GPU가 할당되어 임대 시간이 시작되었습니다. 임대 종료는 " + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul")).format(until) + " (한국 시간)입니다. 콘솔에서 가상머신을 선택하고 연결해 주세요. 가상머신 연결은 동의한 뒤 진행됩니다.", "gpu.allocated:" + a.id());
        }
    }
}
