package kr.ac.pusan.pickle.provisioning;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.VmTemplate;
import kr.ac.pusan.pickle.inventory.VmTemplateRepository;
import kr.ac.pusan.pickle.ipam.AllocationStatus;
import kr.ac.pusan.pickle.ipam.IpAllocation;
import kr.ac.pusan.pickle.ipam.IpAllocationRepository;
import kr.ac.pusan.pickle.ipam.IpPool;
import kr.ac.pusan.pickle.ipam.IpPoolRepository;
import kr.ac.pusan.pickle.ipam.IpamService;
import kr.ac.pusan.pickle.mail.MailMessage;
import kr.ac.pusan.pickle.mail.MailSender;
import kr.ac.pusan.pickle.proxmox.ProxmoxApiException;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.proxmox.ProxmoxTaskFailedException;
import kr.ac.pusan.pickle.proxmox.ProxmoxTimeoutException;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmEvent;
import kr.ac.pusan.pickle.vm.VmEventRepository;
import kr.ac.pusan.pickle.vm.VmEventType;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.vmrequest.VmRequestReview;
import kr.ac.pusan.pickle.vmrequest.VmRequestReviewRepository;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The real M3 provision pipeline (docs/plan/03): ten idempotent steps from
 * guard to finalize, resumable at {@code provisioning_tasks.current_step}.
 * Runs inside a JobRunr worker; deliberately NOT transactional as a whole —
 * each step commits its own small DB writes so a crash between Proxmox calls
 * never holds a transaction open, and re-runs re-check actual state (vmid
 * reuse, clone-exists guard, CAS transitions) instead of trusting memory.
 *
 * <p>Failure policy (docs/plan/03): transient errors retry with backoff
 * {@link #RETRY_BACKOFF} up to {@link #MAX_STEP_ATTEMPTS} attempts per step —
 * self-scheduled via {@link JobScheduler}, which is the only retry mechanism
 * ({@code @Job retries = 0}). Permanent failures compensate by step range:
 * place/alloc release the IP and error the VM; vmid/clone/config additionally
 * destroy the half-created Proxmox VM (ERROR = no underlying VM, terminal);
 * resize/start/verify/finalize park as NEEDS_ADMIN without destroying
 * anything.</p>
 *
 * <p>The generated initial password never appears in any log statement or
 * exception message; only the vms row (plaintext until first view + BCrypt
 * hash) and the cloud-init {@code cipassword} form field carry it.</p>
 */
@Component
public class ProvisionVmJob implements ProvisioningService {

    static final int MAX_STEP_ATTEMPTS = 3;
    static final String COMPLETED_DETAIL = "프로비저닝 완료";

    private static final Logger log = LoggerFactory.getLogger(ProvisionVmJob.class);

    private static final List<Duration> RETRY_BACKOFF =
            List.of(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofMinutes(5));

    private static final List<ProvisioningTaskStatus> LIVE_STATUSES = List.of(
            ProvisioningTaskStatus.PENDING, ProvisioningTaskStatus.RUNNING,
            ProvisioningTaskStatus.RETRYING, ProvisioningTaskStatus.NEEDS_ADMIN);

    /** Guest-agent readiness poll after start (docs/plan/03: 5 s / 5 min). */
    private static final Duration AGENT_PING_INTERVAL = Duration.ofSeconds(5);
    private static final Duration AGENT_PING_TIMEOUT = Duration.ofMinutes(5);

    private static final int PASSWORD_LENGTH = 24;
    private static final char[] PASSWORD_ALPHABET =
            ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#%^*-_+=")
                    .toCharArray();

    private final VmRepository vmRepository;
    private final VmEventRepository vmEventRepository;
    private final ProvisioningTaskRepository taskRepository;
    private final NodeRepository nodeRepository;
    private final VmTemplateRepository templateRepository;
    private final VmRequestReviewRepository reviewRepository;
    private final IpPoolRepository poolRepository;
    private final IpAllocationRepository allocationRepository;
    private final IpamService ipamService;
    private final NodePlacementService placementService;
    private final ProxmoxClient proxmox;
    private final JobScheduler jobScheduler;
    private final PasswordEncoder passwordEncoder;
    private final MailSender mailSender;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public ProvisionVmJob(VmRepository vmRepository, VmEventRepository vmEventRepository,
            ProvisioningTaskRepository taskRepository, NodeRepository nodeRepository,
            VmTemplateRepository templateRepository, VmRequestReviewRepository reviewRepository,
            IpPoolRepository poolRepository, IpAllocationRepository allocationRepository,
            IpamService ipamService, NodePlacementService placementService, ProxmoxClient proxmox,
            JobScheduler jobScheduler, PasswordEncoder passwordEncoder, MailSender mailSender,
            GroupMemberRepository groupMemberRepository, UserRepository userRepository,
            ObjectMapper objectMapper) {
        this.vmRepository = vmRepository;
        this.vmEventRepository = vmEventRepository;
        this.taskRepository = taskRepository;
        this.nodeRepository = nodeRepository;
        this.templateRepository = templateRepository;
        this.reviewRepository = reviewRepository;
        this.poolRepository = poolRepository;
        this.allocationRepository = allocationRepository;
        this.ipamService = ipamService;
        this.placementService = placementService;
        this.proxmox = proxmox;
        this.jobScheduler = jobScheduler;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.groupMemberRepository = groupMemberRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Job(name = "provision-vm %0", retries = 0)
    public void provisionVm(long vmId) {
        ProvisioningTask task = acquireTask(vmId);
        if (task == null) {
            return;
        }
        try {
            runPipeline(task, vmId);
        } catch (PipelineHalted halted) {
            log.info("provision vm {} halted: {}", vmId, halted.getMessage());
        } catch (Exception e) {
            handleFailure(task.getId(), vmId, e);
        }
    }

    // --- task acquisition ----------------------------------------------------

    /**
     * Finds or creates the PROVISION task and claims it via a status CAS.
     * Returns null when this run must no-op: the task is DONE (duplicate
     * approve), FAILED (terminal, compensation already ran), currently RUNNING
     * elsewhere, or another run won the claim race.
     */
    private ProvisioningTask acquireTask(long vmId) {
        ProvisioningTask task = taskRepository
                .findFirstByVmIdAndKindAndStatusInOrderByIdDesc(vmId, ProvisioningTaskKind.PROVISION,
                        LIVE_STATUSES)
                .orElse(null);
        if (task == null) {
            boolean finished = taskRepository.findByVmIdOrderByIdDesc(vmId).stream()
                    .anyMatch(t -> t.getKind() == ProvisioningTaskKind.PROVISION);
            if (finished) {
                // only finished tasks left (DONE/FAILED) — duplicate enqueue no-op
                log.info("provision vm {}: task already finished — no-op", vmId);
                return null;
            }
            try {
                task = taskRepository.saveAndFlush(
                        new ProvisioningTask(vmId, ProvisioningTaskKind.PROVISION));
            } catch (DataIntegrityViolationException e) {
                // partial unique index (vm_id, kind, live): a concurrent run
                // created the row first — reuse it, the CAS below arbitrates.
                task = taskRepository.findFirstByVmIdAndKindAndStatusInOrderByIdDesc(vmId,
                        ProvisioningTaskKind.PROVISION, LIVE_STATUSES).orElse(null);
                if (task == null) {
                    log.info("provision vm {}: lost task-create race and no live task — no-op", vmId);
                    return null;
                }
            }
        }
        Instant now = Instant.now();
        int claimed = switch (task.getStatus()) {
            case PENDING -> taskRepository.startAttempt(task.getId(), now);
            case RETRYING -> taskRepository.resumeAttempt(task.getId(), now);
            // parked task re-enqueued = admin re-run (docs/plan/03 guard step)
            case NEEDS_ADMIN -> taskRepository.reactivate(task.getId(), now);
            // RUNNING: a concurrent worker owns it; DONE/FAILED: nothing to do
            case RUNNING, DONE, FAILED -> 0;
        };
        if (claimed == 0) {
            log.info("provision vm {}: task {} in status {} not claimable — no-op", vmId,
                    task.getId(), task.getStatus());
            return null;
        }
        return taskRepository.findById(task.getId()).orElse(null);
    }

    // --- pipeline ------------------------------------------------------------

    private void runPipeline(ProvisioningTask task, long vmId) {
        for (int step = task.getCurrentStep(); step <= ProvisioningStep.FINALIZE.index(); step++) {
            Vm vm = vmRepository.findById(vmId).orElse(null);
            if (vm == null) {
                taskRepository.fail(task.getId(), "VM 행이 존재하지 않습니다", Instant.now());
                throw new PipelineHalted("vm row missing");
            }
            switch (ProvisioningStep.of(step)) {
                case GUARD -> guard(task, vm);
                case PLACE -> place(vm);
                case ALLOC_IP -> allocIp(vm);
                case VMID -> assignVmid(vm);
                case CLONE -> clone(vm);
                case CONFIG -> configure(vm);
                case RESIZE -> resize(vm);
                case START -> start(vm);
                case VERIFY -> verify(vm);
                case FINALIZE -> {
                    finalizeVm(task, vm);
                    return;
                }
            }
            if (taskRepository.advanceStep(task.getId(), step, Instant.now()) == 0) {
                // a concurrent run moved the pointer — stop, it owns the rest
                throw new PipelineHalted("lost step-advance race at step " + step);
            }
            log.info("provision vm {}: step {} ({}) done", vmId, step,
                    ProvisioningStep.of(step).label());
        }
    }

    /** Step 0: only CREATING VMs (or NEEDS_ADMIN on admin re-run) proceed. */
    private void guard(ProvisioningTask task, Vm vm) {
        if (vm.getStatus() == VmStatus.CREATING) {
            return;
        }
        if (vm.getStatus() == VmStatus.NEEDS_ADMIN
                && vmRepository.transitionStatus(vm.getId(), VmStatus.NEEDS_ADMIN, VmStatus.CREATING,
                        "관리자 재실행으로 프로비저닝 재개", Instant.now()) == 1) {
            return;
        }
        taskRepository.fail(task.getId(),
                "VM 상태 " + vm.getStatus() + "에서는 프로비저닝을 진행할 수 없습니다", Instant.now());
        throw new PipelineHalted("vm status " + vm.getStatus() + " fails the guard");
    }

    /** Step 1: confirm the node (admin-forced node from the approval wins). */
    private void place(Vm vm) {
        VmTemplate template = templateRepository.findById(vm.getTemplateId()).orElseThrow(
                () -> new IllegalStateException("템플릿 " + vm.getTemplateId() + "이 존재하지 않습니다"));
        Long forcedNodeId = reviewRepository.findByRequestId(vm.getRequestId())
                .map(VmRequestReview::getNodeId).orElse(null);
        Node node = placementService.place(vm, template, forcedNodeId);
        vmRepository.assignNode(vm.getId(), node.getId(), Instant.now());
    }

    /** Step 2: allocate an IP from the node's pool (skip when already done). */
    private void allocIp(Vm vm) {
        if (vm.getIpAllocationId() != null) {
            return;
        }
        Node node = node(vm);
        if (node.getIpPoolId() == null) {
            throw new IllegalStateException("노드 " + node.getName() + "에 IP 풀이 지정되지 않았습니다");
        }
        IpAllocation allocation = allocationRepository
                .findFirstByVmIdAndStatusOrderByIdDesc(vm.getId(), AllocationStatus.ALLOCATED)
                .orElseGet(() -> ipamService.allocate(node.getIpPoolId(), vm.getId()));
        vmRepository.assignIpAllocation(vm.getId(), allocation.getId(), Instant.now());
    }

    /** Step 3: reserve the VMID (reuse a stored one — crash guard). */
    private void assignVmid(Vm vm) {
        if (vm.getProxmoxVmid() != null) {
            return;
        }
        int vmid = proxmox.nextId(node(vm).getApiHost());
        // vms.proxmox_vmid is globally unique; losing a nextid race to another
        // pipeline throws DataIntegrityViolationException → retried (transient).
        vmRepository.assignProxmoxVmid(vm.getId(), vmid, Instant.now());
    }

    /** Step 4: full clone of the template — only if the VMID does not exist yet. */
    private void clone(Vm vm) {
        Node node = node(vm);
        int vmid = requireVmid(vm);
        if (vmExists(node, vmid)) {
            log.info("provision vm {}: vmid {} already exists — clone skipped", vm.getId(), vmid);
            return;
        }
        VmTemplate template = templateRepository.findById(vm.getTemplateId()).orElseThrow();
        String upid = proxmox.clone(node.getApiHost(), node.getName(), template.getProxmoxVmid(),
                vmid, vm.getHostname());
        proxmox.awaitTask(node.getApiHost(), node.getName(), upid);
    }

    /**
     * Step 5: cloud-init and hardware config. Generates the initial password
     * (24-char CSPRNG), pushes it as {@code cipassword} and stores plaintext +
     * BCrypt hash on the vms row. A re-run regenerates and overwrites both
     * sides, so DB and guest can never disagree after the step completes.
     */
    private void configure(Vm vm) {
        Node node = node(vm);
        IpAllocation allocation = requireAllocation(vm);
        IpPool pool = poolRepository.findById(allocation.getPoolId()).orElseThrow();
        String ip = hostAddress(allocation.getIp());
        String password = generatePassword();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("cores", String.valueOf(vm.getVcpu()));
        params.put("memory", String.valueOf(vm.getMemoryMb()));
        params.put("ciuser", vm.getSshUsername());
        params.put("cipassword", password);
        params.put("ipconfig0", "ip=" + ip + "/" + cidrPrefix(pool.getCidr())
                + ",gw=" + hostAddress(pool.getGateway()));
        firstDns(pool).ifPresent(dns -> params.put("nameserver", dns));
        params.put("net0", "virtio,bridge=" + node.getVmBridge());
        params.put("onboot", "1");
        params.put("tags", "pickle");
        proxmox.config(node.getApiHost(), node.getName(), requireVmid(vm), params);

        vmRepository.storeInitialCredentials(vm.getId(), password, passwordEncoder.encode(password),
                Instant.now());
    }

    /**
     * Step 6: grow scsi0 to the granted size (absolute {@code <N>G}). PVE
     * rejects a resize to at-or-below the current size with a permanent
     * "shrinking" error — that means the disk is already at least the target
     * (template ≥ target, or a re-run after a crash), so it counts as done.
     */
    private void resize(Vm vm) {
        Node node = node(vm);
        try {
            String upid = proxmox.resize(node.getApiHost(), node.getName(), requireVmid(vm),
                    "scsi0", vm.getDiskGb() + "G");
            proxmox.awaitTask(node.getApiHost(), node.getName(), upid);
        } catch (ProxmoxApiException e) {
            if (!e.isTransient() && e.getMessage() != null
                    && e.getMessage().toLowerCase().contains("shrink")) {
                log.info("provision vm {}: disk already at/above {}G — resize skipped",
                        vm.getId(), vm.getDiskGb());
                return;
            }
            throw e;
        }
    }

    /** Step 7: start the VM, then wait for the guest agent to answer. */
    private void start(Vm vm) {
        Node node = node(vm);
        int vmid = requireVmid(vm);
        try {
            String upid = proxmox.start(node.getApiHost(), node.getName(), vmid);
            proxmox.awaitTask(node.getApiHost(), node.getName(), upid);
        } catch (ProxmoxTaskFailedException e) {
            if (e.exitstatus() == null || !e.exitstatus().contains("already running")) {
                throw e;
            }
            // re-run after a crash between start and step-advance — fine
        }
        long deadline = System.nanoTime() + AGENT_PING_TIMEOUT.toNanos();
        while (!proxmox.agentPing(node.getApiHost(), node.getName(), vmid)) {
            if (System.nanoTime() >= deadline) {
                throw new ProxmoxTimeoutException("qemu-guest-agent가 " + AGENT_PING_TIMEOUT
                        + " 안에 응답하지 않았습니다 (vmid " + vmid + ")");
            }
            sleep(AGENT_PING_INTERVAL);
        }
    }

    /** Step 8: the guest must actually carry the allocated IP. */
    private void verify(Vm vm) {
        Node node = node(vm);
        String expected = hostAddress(requireAllocation(vm).getIp());
        boolean found = proxmox.agentNetworkInterfaces(node.getApiHost(), node.getName(),
                        requireVmid(vm)).stream()
                .flatMap(iface -> iface.ipAddresses().stream())
                .anyMatch(address -> expected.equals(address.ipAddress()));
        if (!found) {
            // cloud-init may still be applying the network config — retryable
            throw new RetryableStepException(
                    "게스트 네트워크에서 기대 IP " + expected + "를 찾지 못했습니다");
        }
    }

    /** Step 9: CREATING → RUNNING, task DONE, CREATE event, owner mail. */
    private void finalizeVm(ProvisioningTask task, Vm vm) {
        Instant now = Instant.now();
        int transitioned = vmRepository.transitionStatus(vm.getId(), VmStatus.CREATING,
                VmStatus.RUNNING, COMPLETED_DETAIL, now);
        if (transitioned == 1) {
            // event + mail only on the run that actually flipped the status,
            // so a crashed-and-resumed finalize cannot duplicate them
            String ip = Optional.ofNullable(vm.getIpAllocationId())
                    .flatMap(allocationRepository::findById)
                    .map(a -> hostAddress(a.getIp())).orElse(null);
            vmEventRepository.save(new VmEvent(vm.getId(), VmEventType.CREATE, null,
                    "프로비저닝 완료 (vmid " + vm.getProxmoxVmid() + ", ip " + ip + ")"));
            sendCreatedMail(vm, ip);
        }
        taskRepository.complete(task.getId(), now);
        log.info("provision vm {} finished (vmid {})", vm.getId(), vm.getProxmoxVmid());
    }

    // --- failure handling (docs/plan/03 retry & compensation) -----------------

    private void handleFailure(long taskId, long vmId, Exception e) {
        Instant now = Instant.now();
        ProvisioningTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.getStatus() != ProvisioningTaskStatus.RUNNING) {
            log.warn("provision vm {} failed but task {} is not RUNNING — nothing to do",
                    vmId, taskId, e);
            return;
        }
        int step = task.getCurrentStep();
        String error = "단계 " + step + "(" + ProvisioningStep.of(step).label() + ") 실패: "
                + summarize(e);
        log.warn("provision vm {} failed at step {} (attempt {}): {}", vmId, step,
                task.getAttempts(), summarize(e), e);

        if (isRetryable(e) && task.getAttempts() <= MAX_STEP_ATTEMPTS
                && taskRepository.markRetrying(taskId, error, now) == 1) {
            Duration backoff = RETRY_BACKOFF.get(Math.min(task.getAttempts(), MAX_STEP_ATTEMPTS) - 1);
            var jobId = jobScheduler.schedule(now.plus(backoff), () -> provisionVm(vmId));
            taskRepository.attachJobrunrJob(taskId, jobId.toString(), Instant.now());
            log.info("provision vm {}: retry scheduled in {} (attempt {} of {})", vmId, backoff,
                    task.getAttempts(), MAX_STEP_ATTEMPTS);
            return;
        }

        if (step <= ProvisioningStep.ALLOC_IP.index()) {
            // steps 0–2: nothing exists on Proxmox — release the IP and error out
            releaseIp(vmId);
            taskRepository.fail(taskId, error, now);
            vmRepository.transitionStatus(vmId, VmStatus.CREATING, VmStatus.ERROR,
                    "생성 실패: " + summarize(e), now);
        } else if (step <= ProvisioningStep.CONFIG.index()) {
            compensate(taskId, vmId, error, e);
        } else {
            // steps 6–9: the VM exists and may hold user-visible state — park it
            taskRepository.park(taskId, error, now);
            vmRepository.transitionStatus(vmId, VmStatus.CREATING, VmStatus.NEEDS_ADMIN,
                    "프로비저닝 중 오류가 발생해 관리자 확인 대기 중입니다", now);
        }
    }

    /**
     * Compensation for steps 3–5: destroy the half-created VM if it exists,
     * release the IP, clear the vmid, then FAILED/ERROR (contract: ERROR is
     * terminal with no underlying VM). If the cleanup itself fails the task is
     * parked instead, so a Proxmox outage never strands invisible resources.
     */
    private void compensate(long taskId, long vmId, String error, Exception cause) {
        Instant now = Instant.now();
        Vm vm = vmRepository.findById(vmId).orElse(null);
        try {
            Integer vmid = vm != null ? vm.getProxmoxVmid() : null;
            if (vm != null && vmid != null) {
                Node node = node(vm);
                if (vmExists(node, vmid)) {
                    String upid = proxmox.delete(node.getApiHost(), node.getName(), vmid);
                    proxmox.awaitTask(node.getApiHost(), node.getName(), upid);
                    log.info("provision vm {}: compensation destroyed half-created vmid {}",
                            vmId, vmid);
                }
            }
        } catch (RuntimeException cleanupFailure) {
            log.error("provision vm {}: compensation failed — parking for an operator", vmId,
                    cleanupFailure);
            taskRepository.park(taskId, error + " (보상 실패: " + summarize(cleanupFailure) + ")", now);
            vmRepository.transitionStatus(vmId, VmStatus.CREATING, VmStatus.NEEDS_ADMIN,
                    "생성 실패 후 자원 정리에 실패해 관리자 확인이 필요합니다", now);
            return;
        }
        releaseIp(vmId);
        vmRepository.clearProxmoxVmid(vmId, now);
        taskRepository.fail(taskId, error, now);
        vmRepository.transitionStatus(vmId, VmStatus.CREATING, VmStatus.ERROR,
                "생성 실패: " + summarize(cause), now);
    }

    private void releaseIp(long vmId) {
        Long allocationId = vmRepository.findById(vmId).map(Vm::getIpAllocationId).orElse(null);
        if (allocationId == null) {
            // crash window: the allocation may exist without the vms column set
            allocationId = allocationRepository
                    .findFirstByVmIdAndStatusOrderByIdDesc(vmId, AllocationStatus.ALLOCATED)
                    .map(IpAllocation::getId).orElse(null);
        }
        if (allocationId != null) {
            ipamService.release(allocationId);
        }
    }

    private static boolean isRetryable(Exception e) {
        return (e instanceof ProxmoxApiException api && api.isTransient())
                || e instanceof ProxmoxTimeoutException
                || e instanceof RetryableStepException
                // vmid claim race with a concurrent pipeline (unique index)
                || e instanceof DataIntegrityViolationException;
    }

    // --- notifications ---------------------------------------------------------

    /** Creation mail to the owning group's OWNERs; failure never fails the VM. */
    private void sendCreatedMail(Vm vm, String ip) {
        try {
            List<String> recipients = groupMemberRepository
                    .findByGroupIdOrderByIdAsc(vm.getGroupId()).stream()
                    .filter(member -> member.getRole() == GroupMemberRole.OWNER)
                    .map(member -> userRepository.findById(member.getUserId()))
                    .flatMap(Optional::stream)
                    .map(User::getEmail)
                    .toList();
            if (recipients.isEmpty()) {
                log.warn("provision vm {}: no group OWNER to notify", vm.getId());
                return;
            }
            String subject = "[Pickle] VM 생성 완료: " + vm.getHostname();
            String body = """
                    신청하신 VM이 생성되어 실행 중입니다.

                    - 호스트명: %s
                    - 내부 IP: %s
                    - SSH 계정: %s
                    - 초기 비밀번호: 콘솔의 VM 상세 화면에서 딱 1회만 확인할 수 있습니다.

                    [중요] 플랫폼은 VM 데이터를 백업하지 않습니다(이용자 책임).
                    중요한 데이터는 반드시 직접 백업해 주세요.
                    """.formatted(vm.getHostname(), ip != null ? ip : "(확인 중)", vm.getSshUsername());
            for (String to : recipients) {
                mailSender.send(new MailMessage(to, subject, body));
            }
        } catch (RuntimeException e) {
            log.error("provision vm {}: creation mail failed", vm.getId(), e);
        }
    }

    // --- helpers ---------------------------------------------------------------

    private Node node(Vm vm) {
        return nodeRepository.findById(vm.getNodeId()).orElseThrow(
                () -> new IllegalStateException("노드 " + vm.getNodeId() + "가 존재하지 않습니다"));
    }

    private static int requireVmid(Vm vm) {
        if (vm.getProxmoxVmid() == null) {
            throw new IllegalStateException("proxmox_vmid가 아직 지정되지 않았습니다 (vm " + vm.getId() + ")");
        }
        return vm.getProxmoxVmid();
    }

    private IpAllocation requireAllocation(Vm vm) {
        if (vm.getIpAllocationId() == null) {
            throw new IllegalStateException("IP가 아직 할당되지 않았습니다 (vm " + vm.getId() + ")");
        }
        return allocationRepository.findById(vm.getIpAllocationId()).orElseThrow();
    }

    /** Does the VMID exist on the cluster? Guards clone and compensation delete. */
    private boolean vmExists(Node node, int vmid) {
        return proxmox.clusterResources(node.getApiHost(), "vm").stream()
                .anyMatch(resource -> Objects.equals(resource.vmid(), vmid));
    }

    private String generatePassword() {
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            password.append(PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)]);
        }
        return password.toString();
    }

    /** Strips an inet prefix suffix, e.g. {@code 172.29.1.5/16 → 172.29.1.5}. */
    private static String hostAddress(String inet) {
        int slash = inet.indexOf('/');
        return slash >= 0 ? inet.substring(0, slash) : inet;
    }

    private static int cidrPrefix(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            throw new IllegalStateException("IP 풀 CIDR 형식이 잘못되었습니다: " + cidr);
        }
        return Integer.parseInt(cidr.substring(slash + 1));
    }

    private Optional<String> firstDns(IpPool pool) {
        JsonNode node = objectMapper.readTree(pool.getDns());
        if (node.isArray() && !node.isEmpty()) {
            return Optional.of(node.get(0).asString());
        }
        return Optional.empty();
    }

    /** One-line failure summary for last_error/status_detail (Korean UI copy). */
    private static String summarize(Throwable e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        message = message.replaceAll("\\s+", " ").strip();
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProxmoxTimeoutException("게스트 에이전트 대기 중 인터럽트", e);
        }
    }

    /** Control-flow stop: this run must not touch the task any further. */
    private static final class PipelineHalted extends RuntimeException {
        PipelineHalted(String message) {
            super(message);
        }
    }

    /** A logically-retryable condition that is not a transport/API failure. */
    private static final class RetryableStepException extends RuntimeException {
        RetryableStepException(String message) {
            super(message);
        }
    }
}
