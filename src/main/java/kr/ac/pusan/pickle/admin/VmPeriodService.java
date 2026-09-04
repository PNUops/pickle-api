package kr.ac.pusan.pickle.admin;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.VmPeriodUpdateRequest;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.AdminVmAccess;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmActorKind;
import kr.ac.pusan.pickle.vm.VmEvent;
import kr.ac.pusan.pickle.vm.VmEventRepository;
import kr.ac.pusan.pickle.vm.VmEventType;
import kr.ac.pusan.pickle.vm.VmQueryService;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.vm.dto.VmDetailResponse;
import org.springframework.http.HttpStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract {@code PATCH /admin/vms/{vmId}/period}: synchronous usage
 * period change. Clearing both expiry markers in the same CAS is the whole
 * point — an expiry-stopped VM becomes startable again ({@code VM_EXPIRED}
 * lifted) and the notice ladder re-arms for the new end date. The org tier is
 * limited to the VMs of the organisations it operates (anything else answers
 * 404), so a read-only role reaches none of it; deletion-bound VMs
 * answer 409 {@code VM_INVALID_STATE}. Date semantics are KST, endDate
 * inclusive.
 */
@Service
public class VmPeriodService {

    private static final List<VmStatus> EXCLUDED_STATUSES =
            List.of(VmStatus.DELETED, VmStatus.DELETING);

    private final VmRepository vmRepository;
    private final VmEventRepository vmEventRepository;
    private final VmQueryService vmQueryService;
    private final AdminVmAccess adminVmAccess;
    private final AuditService auditService;
    private final Clock clock;

    public VmPeriodService(VmRepository vmRepository, VmEventRepository vmEventRepository,
            VmQueryService vmQueryService, AdminVmAccess adminVmAccess, AuditService auditService,
            Clock clock) {
        this.vmRepository = vmRepository;
        this.vmEventRepository = vmEventRepository;
        this.vmQueryService = vmQueryService;
        this.adminVmAccess = adminVmAccess;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public VmDetailResponse updatePeriod(AuthenticatedUser actor, UUID publicVmId,
            VmPeriodUpdateRequest request, String ip) {
        Vm vm = adminVmAccess.requireWritableVm(actor, publicVmId);
        long vmId = vm.getId();
        LocalDate newStart = request.startDate() != null ? request.startDate() : vm.getStartDate();
        LocalDate newEnd = resolveEndDate(request, newStart);
        requireNotDeletionBound(vm);
        if (vmRepository.updatePeriod(vmId, newStart, newEnd, EXCLUDED_STATUSES,
                Instant.now()) == 0) {
            // raced with a schedule-delete/delete accept — same 409 as the pre-check
            throw deletionBound();
        }
        vmEventRepository.save(new VmEvent(vmId, VmEventType.PERIOD_UPDATE, actor.id(), VmActorKind.ADMIN,
                "기간 변경: %s ~ %s → %s ~ %s".formatted(
                        vm.getStartDate(), vm.getEndDate(), newStart, periodLabel(newEnd))));
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.VM_PERIOD_UPDATE, "vm", vm.getPublicId(),
                Map.of("old", Map.of("startDate", String.valueOf(vm.getStartDate()),
                                "endDate", String.valueOf(vm.getEndDate())),
                        "new", Map.of("startDate", String.valueOf(newStart),
                                "endDate", String.valueOf(newEnd))),
                ip);
        // Admin period edit is org-scoped, not workspace-membership-scoped, so the
        // requester holds no grant on this VM → myResourceRole null.
        return vmQueryService.detailOf(vmRepository.findById(vmId).orElseThrow(), null);
    }

    /** 감사와 이벤트 본문에서 종료일 없는 기간을 부르는 말. */
    private static String periodLabel(@Nullable LocalDate endDate) {
        return endDate == null ? "무기한" : endDate.toString();
    }

    /**
     * 새 종료일. 지우기를 요청했으면 null이고, 그것이 무기한이다.
     *
     * <p>둘을 함께 받지 않는 것은 어느 쪽이 이겼는지 호출자가 알 수 없게 되기
     * 때문이다. 둘 다 없는 요청도 거절한다. 기간 변경인데 기간을 말하지 않았다.</p>
     */
    private @Nullable LocalDate resolveEndDate(VmPeriodUpdateRequest request,
            @Nullable LocalDate startDate) {
        boolean clearing = Boolean.TRUE.equals(request.clearEndDate());
        if (clearing && request.endDate() != null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("endDate",
                    "종료일을 지우면서 동시에 지정할 수는 없습니다.")));
        }
        if (clearing) {
            return null;
        }
        if (request.endDate() == null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("endDate",
                    "종료일을 정하거나 무기한으로 바꿔 주세요.")));
        }
        validateDates(request.endDate(), startDate);
        return request.endDate();
    }

    private void validateDates(LocalDate endDate, @Nullable LocalDate startDate) {
        LocalDate today = ClockConfig.todayKst(clock);
        if (endDate.isBefore(today)) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("endDate",
                    "종료일은 오늘(KST) 이후여야 합니다.")));
        }
        if (startDate != null && endDate.isBefore(startDate)) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("endDate",
                    "종료일은 시작일보다 이르면 안 됩니다.")));
        }
    }

    private void requireNotDeletionBound(Vm vm) {
        if (EXCLUDED_STATUSES.contains(vm.getStatus()) || vm.getDeleteScheduledFor() != null
                || vm.getDeleteRequestedAt() != null) {
            throw deletionBound();
        }
    }

    private static ApiException deletionBound() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                "현재 상태에서는 수행할 수 없는 작업입니다",
                "삭제가 예약되었거나 진행 중인 VM은 기간을 변경할 수 없습니다.");
    }

}
