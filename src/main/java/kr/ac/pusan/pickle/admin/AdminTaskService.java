package kr.ac.pusan.pickle.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.admin.dto.AdminTaskResponse;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.provisioning.DeleteVmJob;
import kr.ac.pusan.pickle.provisioning.ProvisioningService;
import kr.ac.pusan.pickle.provisioning.ProvisioningTask;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskKind;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskRepository;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskStatus;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * SYS_ADMIN task queue (contract {@code listAdminTasks}/{@code retryAdminTask}):
 * every VM async task with its VM/org context, and the NEEDS_ADMIN retry. The
 * retry CAS parks the task back to RETRYING — never RUNNING — because the
 * pipeline jobs claim the run themselves (RETRYING → RUNNING counting the
 * attempt); the job is enqueued only after the commit, JobRunr committing on
 * its own connection.
 */
@Service
public class AdminTaskService {

    private final ProvisioningTaskRepository taskRepository;
    private final VmRepository vmRepository;
    private final OrgRepository orgRepository;
    private final ProvisioningService provisioningService;
    private final DeleteVmJob deleteVmJob;
    private final JobScheduler jobScheduler;
    private final AuditService auditService;

    public AdminTaskService(ProvisioningTaskRepository taskRepository, VmRepository vmRepository,
            OrgRepository orgRepository, ProvisioningService provisioningService,
            DeleteVmJob deleteVmJob, JobScheduler jobScheduler, AuditService auditService) {
        this.taskRepository = taskRepository;
        this.vmRepository = vmRepository;
        this.orgRepository = orgRepository;
        this.provisioningService = provisioningService;
        this.deleteVmJob = deleteVmJob;
        this.jobScheduler = jobScheduler;
        this.auditService = auditService;
    }

    /** Newest-updated first with status/kind/vmId filters. */
    public PageResponse<AdminTaskResponse> list(ProvisioningTaskStatus status,
            ProvisioningTaskKind kind, Long vmId, int page, int size) {
        Specification<ProvisioningTask> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (kind != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("kind"), kind));
        }
        if (vmId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("vmId"), vmId));
        }
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        Page<ProvisioningTask> result = taskRepository.findAll(spec, pageable);

        Map<Long, Vm> vms = vmRepository.findAllById(result.getContent().stream()
                        .map(ProvisioningTask::getVmId).distinct().toList()).stream()
                .collect(Collectors.toMap(Vm::getId, Function.identity()));
        Map<Long, String> orgNames = orgRepository.findAllById(vms.values().stream()
                        .map(Vm::getOrgId).filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Org::getId, Org::getName));

        List<AdminTaskResponse> content = result.getContent().stream()
                .map(task -> {
                    Vm vm = vms.get(task.getVmId());
                    String orgName = vm == null || vm.getOrgId() == null ? null
                            : orgNames.get(vm.getOrgId());
                    return AdminTaskResponse.from(task, vm, orgName);
                })
                .toList();
        return PageResponse.of(content, result);
    }

    /** NEEDS_ADMIN → RETRYING CAS + after-commit re-enqueue of the kind's job. */
    @Transactional
    public MessageResponse retry(AuthenticatedUser actor, long taskId, String ip) {
        ProvisioningTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCodes.RESOURCE_NOT_FOUND, "리소스를 찾을 수 없습니다",
                        "작업을 찾을 수 없습니다."));
        if (taskRepository.requeueForAdminRetry(taskId, Instant.now()) == 0) {
            throw notRetryable();
        }
        long vmId = task.getVmId();
        JobLambda job = switch (task.getKind()) {
            case PROVISION -> () -> provisioningService.provisionVm(vmId);
            case DELETE -> () -> deleteVmJob.deleteVm(vmId);
            // no REINSTALL pipeline exists yet, so no NEEDS_ADMIN REINSTALL
            // task can either — refuse defensively instead of losing the CAS.
            case REINSTALL -> throw notRetryable();
        };
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                jobScheduler.enqueue(job);
            }
        });
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.TASK_RETRY,
                "provisioning_task", taskId,
                Map.of("vmId", vmId, "kind", task.getKind().name()), ip);
        return new MessageResponse("작업 재시도를 접수했습니다. 잠시 후 작업 상태가 갱신됩니다.");
    }

    private ApiException notRetryable() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.TASK_NOT_RETRYABLE,
                "재시도할 수 없는 작업입니다",
                "관리자 개입 대기(NEEDS_ADMIN) 상태의 작업만 재시도할 수 있습니다.");
    }
}
