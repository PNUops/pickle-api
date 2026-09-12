package kr.ac.pusan.pickle.gpu;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.StreamSupport;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.springframework.stereotype.Component;

@Component
public class GpuLeaseJob {
    private final GpuStore store;
    private final GpuMutationService mutations;
    private final GpuNotices notices;
    private final SettingsService settings;
    private final Clock clock;
    public GpuLeaseJob(GpuStore store, GpuMutationService mutations, GpuNotices notices, SettingsService settings, Clock clock) {
        this.store = store; this.mutations = mutations; this.notices = notices; this.settings = settings; this.clock = clock;
    }
    @Recurring(id = "gpu-lease", interval = "PT1M")
    @Job(name = "gpu-lease", retries = 0)
    public void check() {
        for (GpuAllocation a : store.held()) {
            if (!a.leaseEndsAt().isAfter(clock.instant())) {
                try { mutations.expire(a.id()); }
                catch (RuntimeException e) { notices.administrators("임대가 끝난 GPU의 회수를 완료하지 못했습니다. 장치 상태 확인이 필요합니다.", "gpu.expiry.error:" + a.id()); }
                continue;
            }
            try {
                var value = settings.requiredValid(SettingsService.GPU_LEASE_NOTICE_HOURS);
                long remaining = Duration.between(clock.instant(), a.leaseEndsAt()).toSeconds();
                long total = Duration.between(a.allocatedAt(), a.leaseEndsAt()).toSeconds();
                StreamSupport.stream(value.spliterator(), false).map(n -> n.asInt()).sorted(Comparator.naturalOrder())
                        .filter(h -> h * 3600L < total && remaining <= h * 3600L).findFirst().ifPresent(h ->
                            notices.holders(a, "GPU 임대 종료 예정", "GPU 임대 종료까지 " + h + "시간 이하가 남았습니다. 실행 중인 작업을 저장해 주세요.",
                                    "gpu.lease.notice:" + a.id() + ":" + a.leaseEndsAt() + ":" + h));
            } catch (IllegalStateException e) { notices.administrators(e.getMessage(), "gpu.config:" + SettingsService.GPU_LEASE_NOTICE_HOURS); }
        }
    }
}
