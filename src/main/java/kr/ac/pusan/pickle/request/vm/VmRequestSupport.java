package kr.ac.pusan.pickle.request.vm;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.ApproveRequestRequest;
import kr.ac.pusan.pickle.admin.dto.ApproveVmRequestSpec;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.inventory.VmFlavor;
import kr.ac.pusan.pickle.inventory.VmFlavorRepository;
import kr.ac.pusan.pickle.provisioning.ProvisioningService;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.request.RequestStatus;
import kr.ac.pusan.pickle.request.RequestTypeHandler;
import kr.ac.pusan.pickle.request.dto.CreateRequestRequest;
import kr.ac.pusan.pickle.request.vm.dto.CreateVmRequestSpec;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vmsettings.VmSettingsService;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Everything about a request that is particular to VMs. */
@Component
public class VmRequestSupport implements RequestTypeHandler {

    private static final char[] HOSTNAME_SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int HOSTNAME_SUFFIX_LENGTH = 4;
    private static final int HOSTNAME_MAX_ATTEMPTS = 10;

    private final VmRequestDetailRepository detailRepository;
    private final OsImageRepository imageRepository;
    private final VmFlavorRepository flavorRepository;
    private final NodeRepository nodeRepository;
    private final VmRepository vmRepository;
    private final VmSlugPolicy slugPolicy;
    private final VmSettingsService vmSettingsService;
    private final JobScheduler jobScheduler;
    private final ProvisioningService provisioningService;
    private final SecureRandom random = new SecureRandom();

    public VmRequestSupport(VmRequestDetailRepository detailRepository, OsImageRepository imageRepository,
            VmFlavorRepository flavorRepository, NodeRepository nodeRepository, VmRepository vmRepository,
            VmSlugPolicy slugPolicy, VmSettingsService vmSettingsService, JobScheduler jobScheduler,
            ProvisioningService provisioningService) {
        this.detailRepository = detailRepository;
        this.imageRepository = imageRepository;
        this.flavorRepository = flavorRepository;
        this.nodeRepository = nodeRepository;
        this.vmRepository = vmRepository;
        this.slugPolicy = slugPolicy;
        this.vmSettingsService = vmSettingsService;
        this.jobScheduler = jobScheduler;
        this.provisioningService = provisioningService;
    }

    @Override
    public ResourceType type() {
        return ResourceType.VM;
    }

    @Override
    public void validateCreate(CreateRequestRequest form, List<FieldValidationError> errors) {
        CreateVmRequestSpec spec = form.vm();
        if (spec == null) {
            errors.add(new FieldValidationError("vm", "VM 신청 항목(vm)을 입력해 주세요."));
            return;
        }
        // A reference to something that does not exist is a 404 here, as it is
        // for the workspace and the organisation; only a row that exists but may
        // no longer be chosen is a validation error.
        OsImage image = imageRepository.findById(spec.imageId())
                .orElseThrow(() -> notFound("해당 OS 이미지가 존재하지 않습니다."));
        VmFlavor flavor = flavorRepository.findById(spec.flavorId())
                .orElseThrow(() -> notFound("해당 사양 프리셋이 존재하지 않습니다."));
        boolean axesActive = true;
        if (image.getStatus() != CatalogStatus.ACTIVE) {
            errors.add(new FieldValidationError("vm.imageId", "더 이상 선택할 수 없는 OS 이미지입니다."));
            axesActive = false;
        }
        if (flavor.getStatus() != CatalogStatus.ACTIVE) {
            errors.add(new FieldValidationError("vm.flavorId", "더 이상 선택할 수 없는 사양 프리셋입니다."));
            axesActive = false;
        }
        if (axesActive) {
            validateSpec(spec, image, flavor, errors);
        }
        validateSlug(Texts.blankToNull(spec.desiredSlug()), errors);
    }

    @Override
    public void saveDetail(Request request, CreateRequestRequest form) {
        CreateVmRequestSpec spec = form.vm();
        detailRepository.save(new VmRequestDetail(request.getId(), spec.imageId(), spec.flavorId(),
                spec.reqVcpu(), spec.reqMemoryMb(), spec.reqDiskGb(),
                Texts.blankToNull(spec.specReason()), Texts.blankToNull(spec.desiredSlug())));
    }

    @Override
    public Map<String, Object> submitAuditArgs(Request request) {
        VmRequestDetail detail = detail(request);
        return Map.of("imageId", detail.getImageId(), "reqVcpu", detail.getReqVcpu(),
                "reqMemoryMb", detail.getReqMemoryMb(), "reqDiskGb", detail.getReqDiskGb());
    }

    @Override
    public void validateApprove(Request request, ApproveRequestRequest form,
            List<FieldValidationError> errors) {
        ApproveVmRequestSpec spec = form.vm();
        if (spec == null) {
            errors.add(new FieldValidationError("vm", "VM 승인 항목(vm)을 입력해 주세요."));
            return;
        }
        OsImage image = imageRepository.findById(spec.grantedImageId()).orElse(null);
        if (image == null || image.getStatus() != CatalogStatus.ACTIVE) {
            errors.add(new FieldValidationError("vm.grantedImageId", "사용할 수 없는 OS 이미지입니다."));
        } else if (spec.grantedDiskGb() < image.getMinDiskGb()) {
            errors.add(new FieldValidationError("vm.grantedDiskGb",
                    "이 OS 이미지의 최소 디스크 크기는 " + image.getMinDiskGb() + "GiB입니다."));
        }
        if (spec.nodeId() != null) {
            if (!nodeRepository.existsById(spec.nodeId())) {
                errors.add(new FieldValidationError("vm.nodeId", "존재하지 않는 노드입니다."));
            } else if (image != null && !imageRepository.existsByNameAndNodeIdAndStatus(
                    image.getName(), spec.nodeId(), CatalogStatus.ACTIVE)) {
                // Forced node must host the granted image — the provisioning
                // pipeline clones the image on the placed node, so a node without it
                // guarantees a mid-pipeline clone failure.
                errors.add(new FieldValidationError("vm.nodeId", "선택한 노드에 해당 OS 이미지가 없습니다."));
            }
        }
        // Publishing is self-service (v0.22.0): approval no longer touches
        // subdomain names — they are validated at submit and finalized at
        // publish time by PublishingService.
        // VM slug finalization (v0.12.0): the admin accepts/changes the
        // requester's desiredSlug here; null/blank keeps today's auto path.
        // vms.hostname is checked against ALL rows incl. soft-deleted —
        // slugs are never recycled (the unique constraint is the backstop).
        String grantedSlug = Texts.blankToNull(spec.grantedSlug());
        if (grantedSlug != null) {
            int slugErrorsBefore = errors.size();
            slugPolicy.validateSlug(grantedSlug, "vm.grantedSlug", errors);
            if (errors.size() == slugErrorsBefore && vmRepository.existsByHostname(grantedSlug)) {
                errors.add(new FieldValidationError("vm.grantedSlug",
                        "이미 사용 중인 호스트명(슬러그)입니다. 다른 값을 입력하거나 비워서 자동 생성하세요."));
            }
        }
    }

    @Override
    public Materialized materialize(Request request, ApproveRequestRequest form, AuthenticatedUser actor) {
        ApproveVmRequestSpec spec = form.vm();
        OsImage image = imageRepository.findById(spec.grantedImageId()).orElseThrow();
        VmRequestDetail detail = detail(request);
        detail.grant(spec.grantedVcpu(), spec.grantedMemoryMb(), spec.grantedDiskGb(),
                image.getId(), spec.nodeId());

        // Auto placement: the image's node (single-node cluster; the
        // scoring placement step arrives with the provisioning pipeline).
        Long nodeId = spec.nodeId() != null ? spec.nodeId() : image.getNodeId();
        String grantedSlug = Texts.blankToNull(spec.grantedSlug());
        String hostname = grantedSlug != null ? grantedSlug
                : generateHostname(VmSlugPolicy.sanitizeSeed(request.getDisplayName()));
        // The guest admin account comes from the granted image (each
        // distribution ships its own), never from a platform-wide constant.
        Vm vm = vmRepository.save(new Vm(nodeId, request.getWorkspaceId(), request.getOrgId(),
                request.getId(), hostname, hostname, image.getId(), image.getSshUsername(),
                spec.grantedVcpu(), spec.grantedMemoryMb(), spec.grantedDiskGb(),
                form.grantedStartDate(), form.grantedEndDate()));
        // Requester-chosen display name (request form) — seeded as the
        // vm_settings row; audited via the request.approve entry. The seeder
        // sanitizes, so it returns what was actually stored (null when the name
        // collapsed to nothing and no row was written).
        String storedDisplayName = request.getDisplayName() != null
                ? vmSettingsService.initializeDisplayName(vm.getId(), request.getDisplayName(),
                        request.getRequesterId())
                : null;

        long vmId = vm.getId();
        Map<String, Object> auditArgs = new LinkedHashMap<>();
        auditArgs.put("vmId", vmId);
        auditArgs.put("hostname", hostname);
        auditArgs.put("grantedVcpu", spec.grantedVcpu());
        auditArgs.put("grantedMemoryMb", spec.grantedMemoryMb());
        auditArgs.put("grantedDiskGb", spec.grantedDiskGb());
        auditArgs.put("nodeId", nodeId);
        if (storedDisplayName != null) {
            // Records the seeded display name's provenance (initializeDisplayName
            // itself does not audit — this entry is the audit trail). The stored
            // value, not the raw request value: an entry claiming a name that was
            // never written would be a lie in the audit trail.
            auditArgs.put("displayName", storedDisplayName);
        }
        // The OSS JobRunr storage provider writes with its own connection and
        // commits immediately, so an in-transaction enqueue could (a) leave an
        // orphaned durable job if this tx rolls back, or (b) let a worker pick
        // the job before the vm row is visible. Enqueue after commit instead.
        // Trade-off: a crash in the tiny window between commit and enqueue
        // loses the job (VM stays CREATING) — recovered by StaleTaskRecoveryJob,
        // which re-enqueues stuck-CREATING VMs without a PROVISION task
        // (every 10 min; the drift reconciler does NOT see them — its working
        // set is vmid-bearing rows only, and these have no vmid yet).
        return new Materialized(vmId, hostname, auditArgs,
                () -> jobScheduler.enqueue(() -> provisioningService.provisionVm(vmId)));
    }

    private static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", detail);
    }

    private VmRequestDetail detail(Request request) {
        return detailRepository.findById(request.getId()).orElseThrow(
                () -> new IllegalStateException("VM request " + request.getId() + " has no detail row"));
    }

    /**
     * Axis-split validation (V58): the hard floor is the OS image's
     * {@code minDiskGb}; the spec-reason baseline is the chosen flavor's
     * values — requesting below a preset stays free, exceeding it needs a
     * reason (same semantics the OS defaults carried before the split).
     */
    private static void validateSpec(CreateVmRequestSpec spec, OsImage image, VmFlavor flavor,
            List<FieldValidationError> errors) {
        if (spec.reqDiskGb() < image.getMinDiskGb()) {
            errors.add(new FieldValidationError("vm.reqDiskGb",
                    "이 OS의 최소 디스크 크기는 " + image.getMinDiskGb() + "GiB입니다."));
        }
        boolean exceedsFlavor = spec.reqVcpu() > flavor.getVcpu()
                || spec.reqMemoryMb() > flavor.getMemoryMb()
                || spec.reqDiskGb() > flavor.getDiskGb();
        if (exceedsFlavor && Texts.blankToNull(spec.specReason()) == null) {
            errors.add(new FieldValidationError("vm.specReason",
                    "선택한 사양 프리셋을 초과하는 신청에는 사유(specReason)를 입력해야 합니다."));
        }
    }

    /**
     * Desired-slug validation (v0.12.0): pattern/reserved/profanity via
     * {@link VmSlugPolicy}, then uniqueness — against vms.hostname (soft-deleted
     * included, slugs are never recycled) and against other SUBMITTED requests.
     */
    private void validateSlug(String desiredSlug, List<FieldValidationError> errors) {
        if (desiredSlug == null) {
            return;
        }
        int before = errors.size();
        slugPolicy.validateSlug(desiredSlug, "vm.desiredSlug", errors);
        if (errors.size() > before) {
            return;
        }
        if (vmRepository.existsByHostname(desiredSlug)) {
            errors.add(new FieldValidationError("vm.desiredSlug", "이미 사용 중인 호스트명입니다."));
        } else if (detailRepository.existsByDesiredSlugAndRequestStatus(desiredSlug,
                RequestStatus.SUBMITTED)) {
            errors.add(new FieldValidationError("vm.desiredSlug", "이미 신청 중인 호스트명입니다."));
        }
    }

    /**
     * Unique hostname: a sanitized seed plus a short random suffix (the DB
     * unique constraint is the backstop). The seed used to be the workspace
     * slug, which no longer exists.
     */
    private String generateHostname(String seed) {
        for (int attempt = 0; attempt < HOSTNAME_MAX_ATTEMPTS; attempt++) {
            StringBuilder suffix = new StringBuilder(HOSTNAME_SUFFIX_LENGTH);
            for (int i = 0; i < HOSTNAME_SUFFIX_LENGTH; i++) {
                suffix.append(HOSTNAME_SUFFIX_ALPHABET[random.nextInt(HOSTNAME_SUFFIX_ALPHABET.length)]);
            }
            String hostname = seed + "-" + suffix;
            if (!vmRepository.existsByHostname(hostname)) {
                return hostname;
            }
        }
        throw new IllegalStateException("Could not generate a unique hostname from seed " + seed);
    }
}
