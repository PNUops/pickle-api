package kr.ac.pusan.pickle.gpu;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.access.VmAccessService;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.ApproveRequestRequest;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.gpu.dto.ApproveGpuRequestSpec;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.request.RequestTypeHandler;
import kr.ac.pusan.pickle.request.dto.CreateRequestRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.stereotype.Component;

/** Approval creates a queue entry; selecting a VM never authorizes attachment. */
@Component
public class GpuRequestSupport implements RequestTypeHandler {
    private final GpuStore store;
    private final VmAccessService vmAccess;

    public GpuRequestSupport(GpuStore store, VmAccessService vmAccess) {
        this.store = store;
        this.vmAccess = vmAccess;
    }

    @Override public ResourceType type() { return ResourceType.GPU; }

    @Override
    public void validateCreate(CreateRequestRequest form, List<FieldValidationError> errors) {
        if (form.gpu() == null || form.gpu().leaseHours() == null || form.gpu().leaseHours() < 1) {
            errors.add(new FieldValidationError("gpu.leaseHours", "GPU 임대 시간을 직접 입력해 주세요."));
        }
    }

    @Override
    public void saveDetail(Request request, CreateRequestRequest form) {
        Long vmId = null;
        if (form.gpu().vmId() != null) {
            var vm = vmAccess.of(form.gpu().vmId(), request.getRequesterId()).requireAtLeast(ResourceRole.EDITOR,
                    "권한이 없습니다", "가상머신 편집 권한이 필요합니다.");
            if (!vm.getWorkspaceId().equals(request.getWorkspaceId())) {
                throw GpuErrors.invalid("gpu.vmId", "같은 워크스페이스의 가상머신을 선택해 주세요.");
            }
            vmId = vm.getId();
        }
        store.jdbc().update("insert into gpu_request_details(request_id, vm_id, lease_hours) values (?, ?, ?)",
                request.getId(), vmId, form.gpu().leaseHours());
    }

    @Override
    public Map<String, Object> submitAuditArgs(Request request) {
        return Map.of("leaseHours", store.requestSpec(request.getId()).leaseHours());
    }

    @Override
    public void validateApprove(Request request, ApproveRequestRequest form, List<FieldValidationError> errors) {
        ApproveGpuRequestSpec spec = form.gpu();
        if (spec == null || spec.leaseHours() == null || spec.leaseHours() < 1) {
            errors.add(new FieldValidationError("gpu.leaseHours", "승인할 GPU 임대 시간을 직접 입력해 주세요."));
            return;
        }
        if (spec.gpuId() != null && store.gpu(spec.gpuId()).filter(g -> g.status() == GpuStatus.ACTIVE).isEmpty()) {
            errors.add(new FieldValidationError("gpu.gpuId", "사용 가능한 GPU를 선택해 주세요."));
        }
    }

    @Override
    public Materialized materialize(Request request, ApproveRequestRequest form, AuthenticatedUser actor) {
        var spec = form.gpu();
        int priority = spec.priority() == null ? 0 : spec.priority();
        if (priority != 0 && (actor.role() != UserRole.SYS_MANAGER && actor.role() != UserRole.SYS_ADMIN)) {
            throw GpuErrors.forbidden();
        }
        if (priority != 0 && (spec.priorityReason() == null || spec.priorityReason().isBlank())) {
            throw GpuErrors.invalid("gpu.priorityReason", "우선순위 변경 사유를 입력해 주세요.");
        }
        Long gpuId = spec.gpuId() == null ? null : store.gpu(spec.gpuId()).orElseThrow(GpuErrors::notFound).id();
        store.jdbc().update("""
                update gpu_request_details set granted_lease_hours = ?, granted_gpu_id = ?, granted_priority = ?
                where request_id = ?
                """, spec.leaseHours(), gpuId, priority, request.getId());
        long id = store.jdbc().queryForObject("""
                insert into gpu_allocations(request_id, workspace_id, org_id, name, preferred_gpu_id,
                   granted_lease_hours, priority, granted_start_date, granted_end_date)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, request.getId(), request.getWorkspaceId(), request.getOrgId(),
                request.getDisplayName(), gpuId, spec.leaseHours(), priority, form.grantedStartDate(), form.grantedEndDate());
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("leaseHours", spec.leaseHours());
        audit.put("priority", priority);
        if (spec.priorityReason() != null) { audit.put("priorityReason", spec.priorityReason()); }
        return new Materialized(id, request.getDisplayName(), audit, () -> {},
                Map.of("queuePosition", store.queuePosition(store.allocation(id).orElseThrow())));
    }
}
