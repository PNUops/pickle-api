package kr.ac.pusan.pickle.admin;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.admin.dto.VmPeriodUpdateRequest;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmEvent;
import kr.ac.pusan.pickle.vm.VmEventRepository;
import kr.ac.pusan.pickle.vm.VmEventType;
import kr.ac.pusan.pickle.vm.VmQueryService;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.vm.dto.VmDetailResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract {@code PATCH /admin/vms/{vmId}/period}: synchronous usage
 * period change. Clearing both expiry markers in the same CAS is the whole
 * point — an expiry-stopped VM becomes startable again ({@code VM_EXPIRED}
 * lifted) and the notice ladder re-arms for the new end date. ORG_ADMIN is
 * limited to its own org's VMs (cross-org answers 404); deletion-bound VMs
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
    private final AuditService auditService;
    private final Clock clock;

    public VmPeriodService(VmRepository vmRepository, VmEventRepository vmEventRepository,
            VmQueryService vmQueryService, AuditService auditService, Clock clock) {
        this.vmRepository = vmRepository;
        this.vmEventRepository = vmEventRepository;
        this.vmQueryService = vmQueryService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public VmDetailResponse updatePeriod(AuthenticatedUser actor, long vmId,
            VmPeriodUpdateRequest request, String ip) {
        Vm vm = requireOrgScopedVm(actor, vmId);
        LocalDate newStart = request.startDate() != null ? request.startDate() : vm.getStartDate();
        validateDates(request.endDate(), newStart);
        requireNotDeletionBound(vm);
        if (vmRepository.updatePeriod(vmId, newStart, request.endDate(), EXCLUDED_STATUSES,
                Instant.now()) == 0) {
            // raced with a schedule-delete/delete accept — same 409 as the pre-check
            throw deletionBound();
        }
        vmEventRepository.save(new VmEvent(vmId, VmEventType.PERIOD_UPDATE, actor.id(),
                "기간 변경: %s ~ %s → %s ~ %s".formatted(
                        vm.getStartDate(), vm.getEndDate(), newStart, request.endDate())));
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.VM_PERIOD_UPDATE, "vm", vmId,
                Map.of("old", Map.of("startDate", String.valueOf(vm.getStartDate()),
                                "endDate", String.valueOf(vm.getEndDate())),
                        "new", Map.of("startDate", String.valueOf(newStart),
                                "endDate", String.valueOf(request.endDate()))),
                ip);
        // Admin period edit is org-scoped, not group-membership-scoped, so the
        // requester has no group role in this VM's group → myGroupRole null.
        return vmQueryService.detailOf(vmRepository.findById(vmId).orElseThrow(), null);
    }

    private void validateDates(LocalDate endDate, LocalDate startDate) {
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

    /** Org tier sees only its own org's VMs — cross-org and unknown both 404. */
    private Vm requireOrgScopedVm(AuthenticatedUser actor, long vmId) {
        Vm vm = vmRepository.findById(vmId).orElseThrow(VmPeriodService::vmNotFound);
        if (actor.role().isOrgTier() && !vm.getOrgId().equals(actor.orgId())) {
            throw vmNotFound();
        }
        return vm;
    }

    private static ApiException deletionBound() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                "현재 상태에서는 수행할 수 없는 작업입니다",
                "삭제가 예약되었거나 진행 중인 VM은 기간을 변경할 수 없습니다.");
    }

    private static ApiException vmNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 VM이 존재하지 않습니다.");
    }
}
