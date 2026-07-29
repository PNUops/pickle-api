package kr.ac.pusan.pickle.admin;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.admin.dto.ApproveVmRequestRequest;
import kr.ac.pusan.pickle.admin.dto.RejectVmRequestRequest;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.group.Group;
import kr.ac.pusan.pickle.group.GroupRepository;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.TemplateStatus;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.provisioning.ProvisioningService;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vmrequest.VmRequest;
import kr.ac.pusan.pickle.vmrequest.VmRequestAssembler;
import kr.ac.pusan.pickle.vmrequest.VmRequestRepository;
import kr.ac.pusan.pickle.vmrequest.VmRequestReview;
import kr.ac.pusan.pickle.vmrequest.VmRequestReviewRepository;
import kr.ac.pusan.pickle.vmrequest.VmRequestStatus;
import kr.ac.pusan.pickle.vmrequest.VmSlugPolicy;
import kr.ac.pusan.pickle.vmrequest.dto.VmRequestDetailResponse;
import kr.ac.pusan.pickle.vmsettings.VmSettingsService;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Admin approval flow (contract tag {@code admin}, vm-requests subset).
 * ORG_ADMIN is hard-scoped to their own org — requests of other orgs answer
 * 404 (existence stays private, per contract). Approval is a single
 * transaction that writes intent only: review row + CREATING vm row + a
 * JobRunr provisioning job; no Proxmox call happens here.
 */
@Service
public class ApprovalService {

    private static final char[] HOSTNAME_SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int HOSTNAME_SUFFIX_LENGTH = 4;
    private static final int HOSTNAME_MAX_ATTEMPTS = 10;

    private final VmRequestRepository requestRepository;
    private final VmRequestReviewRepository reviewRepository;
    private final VmRequestAssembler assembler;
    private final VmRepository vmRepository;
    private final OsImageRepository imageRepository;
    private final NodeRepository nodeRepository;
    private final GroupRepository groupRepository;
    private final JobScheduler jobScheduler;
    private final ProvisioningService provisioningService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final VmSlugPolicy slugPolicy;
    private final VmSettingsService vmSettingsService;
    private final SecureRandom random = new SecureRandom();

    public ApprovalService(VmRequestRepository requestRepository, VmRequestReviewRepository reviewRepository,
            VmRequestAssembler assembler, VmRepository vmRepository, OsImageRepository imageRepository,
            NodeRepository nodeRepository, GroupRepository groupRepository, JobScheduler jobScheduler,
            ProvisioningService provisioningService, AuditService auditService,
            NotificationService notificationService,
            VmSlugPolicy slugPolicy, VmSettingsService vmSettingsService) {
        this.requestRepository = requestRepository;
        this.reviewRepository = reviewRepository;
        this.assembler = assembler;
        this.vmRepository = vmRepository;
        this.imageRepository = imageRepository;
        this.nodeRepository = nodeRepository;
        this.groupRepository = groupRepository;
        this.jobScheduler = jobScheduler;
        this.provisioningService = provisioningService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.slugPolicy = slugPolicy;
        this.vmSettingsService = vmSettingsService;
    }

    @Transactional(readOnly = true)
    public PageResponse<VmRequestDetailResponse> list(AuthenticatedUser actor, VmRequestStatus status,
            Long orgId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        // Org tier is always pinned to their own org; orgId is sys-tier-only.
        Long scopedOrgId = actor.role().isOrgTier() ? actor.orgId() : orgId;
        if (actor.role().isOrgTier() && scopedOrgId == null) {
            // Defensive: an org-tier actor without a managed org sees nothing.
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
        }
        Page<VmRequest> result;
        if (scopedOrgId != null) {
            result = status != null
                    ? requestRepository.findByOrgIdAndStatus(scopedOrgId, status, pageable)
                    : requestRepository.findByOrgId(scopedOrgId, pageable);
        } else {
            result = status != null
                    ? requestRepository.findByStatus(status, pageable)
                    : requestRepository.findAll(pageable);
        }
        return PageResponse.of(assembler.toDetails(result.getContent()), result);
    }

    @Transactional(readOnly = true)
    public VmRequestDetailResponse get(AuthenticatedUser actor, long requestId) {
        return assembler.toDetail(findScoped(actor, requestId));
    }

    @Transactional
    public VmRequestDetailResponse approve(AuthenticatedUser actor, long requestId,
            ApproveVmRequestRequest form, String ip) {
        VmRequest request = findScopedWithLock(actor, requestId);
        requireSubmitted(request);

        List<FieldValidationError> errors = new ArrayList<>();
        OsImage image = imageRepository.findById(form.grantedTemplateId()).orElse(null);
        if (image == null || image.getStatus() != TemplateStatus.ACTIVE) {
            errors.add(new FieldValidationError("grantedTemplateId", "사용할 수 없는 템플릿입니다."));
        } else if (form.grantedDiskGb() < image.getMinDiskGb()) {
            errors.add(new FieldValidationError("grantedDiskGb",
                    "이 템플릿의 최소 디스크 크기는 " + image.getMinDiskGb() + "GiB입니다."));
        }
        if (form.grantedStartDate() != null && form.grantedEndDate() != null
                && form.grantedEndDate().isBefore(form.grantedStartDate())) {
            errors.add(new FieldValidationError("grantedEndDate", "종료일은 시작일 이후여야 합니다."));
        }
        if (form.nodeId() != null) {
            if (!nodeRepository.existsById(form.nodeId())) {
                errors.add(new FieldValidationError("nodeId", "존재하지 않는 노드입니다."));
            } else if (image != null && !imageRepository.existsByNameAndNodeIdAndStatus(
                    image.getName(), form.nodeId(), TemplateStatus.ACTIVE)) {
                // Forced node must host the granted image — the provisioning
                // pipeline clones the image on the placed node, so a node without it
                // guarantees a mid-pipeline clone failure.
                errors.add(new FieldValidationError("nodeId", "선택한 노드에 해당 템플릿이 없습니다."));
            }
        }
        // Publishing is self-service (v0.22.0): approval no longer touches
        // subdomain names — they are validated at submit and finalized at
        // publish time by PublishingService.
        // VM slug finalization (v0.12.0): the admin accepts/changes the
        // requester's desiredSlug here; null/blank keeps today's auto path.
        // vms.hostname is checked against ALL rows incl. soft-deleted —
        // slugs are never recycled (the unique constraint is the backstop).
        String grantedSlug = Texts.blankToNull(form.grantedSlug());
        if (grantedSlug != null) {
            int slugErrorsBefore = errors.size();
            slugPolicy.validateSlug(grantedSlug, "grantedSlug", errors);
            if (errors.size() == slugErrorsBefore && vmRepository.existsByHostname(grantedSlug)) {
                errors.add(new FieldValidationError("grantedSlug",
                        "이미 사용 중인 호스트명(슬러그)입니다. 다른 값을 입력하거나 비워서 자동 생성하세요."));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }

        reviewRepository.save(VmRequestReview.approve(request.getId(), actor.id(),
                Texts.blankToNull(form.comment()),
                form.grantedVcpu(), form.grantedMemoryMb(), form.grantedDiskGb(), image.getId(),
                form.grantedStartDate(), form.grantedEndDate(), form.nodeId()));
        request.setStatus(VmRequestStatus.APPROVED);

        // Auto placement: the image's node (single-node cluster; the
        // scoring placement step arrives with the provisioning pipeline).
        Long nodeId = form.nodeId() != null ? form.nodeId() : image.getNodeId();
        Group group = groupRepository.findById(request.getGroupId()).orElseThrow();
        String hostname = grantedSlug != null ? grantedSlug : generateHostname(group.getSlug());
        Vm vm = vmRepository.save(new Vm(nodeId, request.getGroupId(), request.getOrgId(),
                request.getId(), hostname, hostname, image.getId(),
                form.grantedVcpu(), form.grantedMemoryMb(), form.grantedDiskGb(),
                form.grantedStartDate(), form.grantedEndDate()));
        // Requester-chosen display name (request form) — seeded as the
        // vm_settings row; audited via the request.approve entry below. The
        // seeder sanitizes, so it returns what was actually stored (null when
        // the name collapsed to nothing and no row was written).
        String storedDisplayName = request.getDisplayName() != null
                ? vmSettingsService.initializeDisplayName(vm.getId(), request.getDisplayName(),
                        request.getRequesterId())
                : null;

        long vmId = vm.getId();
        // The OSS JobRunr storage provider writes with its own connection and
        // commits immediately, so an in-transaction enqueue could (a) leave an
        // orphaned durable job if this tx rolls back, or (b) let a worker pick
        // the job before the vm row is visible. Enqueue after commit instead.
        // Trade-off: a crash in the tiny window between commit and enqueue
        // loses the job (VM stays CREATING) — recovered by StaleTaskRecoveryJob,
        // which re-enqueues stuck-CREATING VMs without a PROVISION task
        // (every 10 min; the drift reconciler does NOT see them — its working
        // set is vmid-bearing rows only, and these have no vmid yet).
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                jobScheduler.enqueue(() -> provisioningService.provisionVm(vmId));
            }
        });

        Map<String, Object> auditArgs = new LinkedHashMap<>();
        auditArgs.put("vmId", vmId);
        auditArgs.put("hostname", hostname);
        auditArgs.put("grantedVcpu", form.grantedVcpu());
        auditArgs.put("grantedMemoryMb", form.grantedMemoryMb());
        auditArgs.put("grantedDiskGb", form.grantedDiskGb());
        auditArgs.put("nodeId", nodeId);
        if (storedDisplayName != null) {
            // Records the seeded display name's provenance (initializeDisplayName
            // itself does not audit — this entry is the audit trail). The stored
            // value, not the raw request value: an entry claiming a name that was
            // never written would be a lie in the audit trail.
            auditArgs.put("displayName", storedDisplayName);
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.REQUEST_APPROVE,
                "vm_request", request.getId(), auditArgs, ip);
        // In-tx insert: the notice exists iff the approval committed.
        Map<String, Object> notifyArgs = new LinkedHashMap<>();
        notifyArgs.put("requestId", request.getId());
        notifyArgs.put("hostname", hostname);
        String reviewComment = Texts.blankToNull(form.comment());
        if (reviewComment != null) {
            notifyArgs.put("comment", reviewComment);
        }
        notificationService.publish(request.getRequesterId(), NotificationEvent.REQUEST_APPROVED,
                notifyArgs, null);
        return assembler.toDetail(request);
    }

    @Transactional
    public VmRequestDetailResponse reject(AuthenticatedUser actor, long requestId,
            RejectVmRequestRequest form, String ip) {
        VmRequest request = findScopedWithLock(actor, requestId);
        requireSubmitted(request);
        reviewRepository.save(VmRequestReview.reject(request.getId(), actor.id(), form.comment().strip()));
        request.setStatus(VmRequestStatus.REJECTED);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.REQUEST_REJECT,
                "vm_request", request.getId(), Map.of("groupId", request.getGroupId()), ip);
        notificationService.publish(request.getRequesterId(), NotificationEvent.REQUEST_REJECTED,
                Map.of("requestId", request.getId(), "comment", form.comment().strip()), null);
        return assembler.toDetail(request);
    }

    /** Org-scoped lookup: unknown id and other-org requests both answer 404. */
    VmRequest findScoped(AuthenticatedUser actor, long requestId) {
        return scoped(actor, requestRepository.findById(requestId).orElse(null));
    }

    private VmRequest findScopedWithLock(AuthenticatedUser actor, long requestId) {
        return scoped(actor, requestRepository.findWithLockById(requestId).orElse(null));
    }

    private VmRequest scoped(AuthenticatedUser actor, VmRequest request) {
        if (request == null
                || (actor.role().isOrgTier() && !request.getOrgId().equals(actor.orgId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "리소스를 찾을 수 없습니다", "해당 신청이 존재하지 않습니다.");
        }
        return request;
    }

    private static void requireSubmitted(VmRequest request) {
        if (request.getStatus() != VmRequestStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.REQUEST_ALREADY_DECIDED,
                    "이미 처리된 신청입니다", "이 신청은 이미 승인, 반려 또는 취소되었습니다.");
        }
    }

    /** Unique hostname: group slug + short random suffix (DB unique as backstop). */
    private String generateHostname(String groupSlug) {
        for (int attempt = 0; attempt < HOSTNAME_MAX_ATTEMPTS; attempt++) {
            StringBuilder suffix = new StringBuilder(HOSTNAME_SUFFIX_LENGTH);
            for (int i = 0; i < HOSTNAME_SUFFIX_LENGTH; i++) {
                suffix.append(HOSTNAME_SUFFIX_ALPHABET[random.nextInt(HOSTNAME_SUFFIX_ALPHABET.length)]);
            }
            String hostname = groupSlug + "-" + suffix;
            if (!vmRepository.existsByHostname(hostname)) {
                return hostname;
            }
        }
        throw new IllegalStateException("Could not generate a unique hostname for slug " + groupSlug);
    }
}
