package kr.ac.pusan.pickle.request.vm;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Map;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.ApproveRequestRequest;
import kr.ac.pusan.pickle.admin.dto.ApproveVmRequestSpec;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.NodeStatus;
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
import org.jspecify.annotations.Nullable;
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
        OsImage image = imageRepository.findByPublicId(spec.imageId())
                .orElseThrow(() -> notFound("해당 OS 이미지가 존재하지 않습니다."));
        // 사양을 직접 적은 신청은 프리셋을 가리키지 않는다. 그때는 사유가 무조건 필요하다.
        VmFlavor flavor = spec.flavorId() == null ? null
                : flavorRepository.findByPublicId(spec.flavorId())
                        .orElseThrow(() -> notFound("해당 사양이 존재하지 않습니다."));
        boolean axesActive = true;
        if (image.getStatus() != CatalogStatus.ACTIVE) {
            errors.add(new FieldValidationError("vm.imageId", "더 이상 선택할 수 없는 OS 이미지입니다."));
            axesActive = false;
        }
        if (flavor != null && flavor.getStatus() != CatalogStatus.ACTIVE) {
            errors.add(new FieldValidationError("vm.flavorId", "더 이상 선택할 수 없는 사양입니다."));
            axesActive = false;
        }
        if (axesActive) {
            validateSpec(spec, image, flavor, errors);
            validateFitsSomeNode(spec, errors);
        }
        validateSlug(Texts.blankToNull(spec.desiredSlug()), errors);
    }

    @Override
    public void saveDetail(Request request, CreateRequestRequest form) {
        CreateVmRequestSpec spec = form.vm();
        // validateCreate already 404'd on an unknown reference, so these resolve.
        long imageId = imageRepository.findByPublicId(spec.imageId()).orElseThrow().getId();
        // 사양을 직접 적은 신청은 가리키는 프리셋이 없다.
        Long flavorId = spec.flavorId() == null ? null
                : flavorRepository.findByPublicId(spec.flavorId()).orElseThrow().getId();
        detailRepository.save(new VmRequestDetail(request.getId(), imageId, flavorId,
                spec.reqVcpu(), spec.reqMemoryMb(), spec.reqDiskGb(),
                Texts.blankToNull(spec.specReason()), Texts.blankToNull(spec.desiredSlug())));
    }

    @Override
    public Map<String, Object> submitAuditArgs(Request request) {
        VmRequestDetail detail = detail(request);
        return Map.of("imageId", imageRepository.findById(detail.getImageId())
                        .map(OsImage::getPublicId).orElse(null),
                "reqVcpu", detail.getReqVcpu(),
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
        OsImage image = imageRepository.findByPublicId(spec.grantedImageId()).orElse(null);
        if (image == null || image.getStatus() != CatalogStatus.ACTIVE) {
            errors.add(new FieldValidationError("vm.grantedImageId", "사용할 수 없는 OS 이미지입니다."));
        } else if (spec.grantedDiskGb() < image.getMinDiskGb()) {
            errors.add(new FieldValidationError("vm.grantedDiskGb",
                    "이 OS 이미지의 최소 디스크 크기는 " + image.getMinDiskGb() + "GiB입니다."));
        }
        if (spec.nodeId() != null) {
            Long nodeId = nodeRepository.findByPublicId(spec.nodeId())
                    .map(kr.ac.pusan.pickle.inventory.Node::getId).orElse(null);
            if (nodeId == null) {
                errors.add(new FieldValidationError("vm.nodeId", "존재하지 않는 노드입니다."));
            } else if (image != null && !imageRepository.existsByNameAndNodeIdAndStatus(
                    image.getName(), nodeId, CatalogStatus.ACTIVE)) {
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
                        "이미 쓰고 있는 이름입니다. 다른 이름을 적거나 비워서 자동으로 정하게 하세요."));
            }
        }
    }

    @Override
    public Materialized materialize(Request request, ApproveRequestRequest form, AuthenticatedUser actor) {
        ApproveVmRequestSpec spec = form.vm();
        OsImage image = imageRepository.findByPublicId(spec.grantedImageId()).orElseThrow();
        VmRequestDetail detail = detail(request);
        Long forcedNodeId = spec.nodeId() == null ? null
                : nodeRepository.findByPublicId(spec.nodeId())
                        .map(kr.ac.pusan.pickle.inventory.Node::getId).orElseThrow();
        detail.grant(spec.grantedVcpu(), spec.grantedMemoryMb(), spec.grantedDiskGb(),
                image.getId(), forcedNodeId);

        // Auto placement: the image's node (single-node cluster; the
        // scoring placement step arrives with the provisioning pipeline).
        Long nodeId = forcedNodeId != null ? forcedNodeId : image.getNodeId();
        String grantedSlug = Texts.blankToNull(spec.grantedSlug());
        String hostname = grantedSlug != null ? grantedSlug
                : generateHostname(VmSlugPolicy.sanitizeSeed(request.getDisplayName(),
                        request.getWorkspaceId()), request.getWorkspaceId());
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
        // Every request carries a name, so there is always one to seed; the
        // seeder still answers null when sanitizing leaves nothing behind.
        String storedDisplayName = vmSettingsService.initializeDisplayName(vm.getId(),
                request.getDisplayName(), request.getRequesterId());

        long vmId = vm.getId();
        Map<String, Object> auditArgs = new LinkedHashMap<>();
        auditArgs.put("vmId", vm.getPublicId());
        auditArgs.put("hostname", hostname);
        auditArgs.put("grantedVcpu", spec.grantedVcpu());
        auditArgs.put("grantedMemoryMb", spec.grantedMemoryMb());
        auditArgs.put("grantedDiskGb", spec.grantedDiskGb());
        auditArgs.put("nodeId", nodeRepository.findById(nodeId)
                .map(kr.ac.pusan.pickle.inventory.Node::getPublicId).orElse(null));
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

    /** Whether the slug policy would accept this name, without collecting the reasons. */
    private boolean policyAccepts(String candidate) {
        List<FieldValidationError> rejected = new ArrayList<>();
        slugPolicy.validateSlug(candidate, "hostname", rejected);
        return rejected.isEmpty();
    }

    private VmRequestDetail detail(Request request) {
        return detailRepository.findById(request.getId()).orElseThrow(
                () -> new IllegalStateException("VM request " + request.getId() + " has no detail row"));
    }

    /**
     * Axis-split validation (V58): the hard floor is the OS image's
     * {@code minDiskGb}, and the spec-reason baseline is whichever spec was
     * chosen. Asking for less than the chosen spec stays free and exceeding it
     * needs a reason, which is what the OS defaults meant before the split.
     *
     * <p>A null flavor is the request that chose nothing and typed its own
     * numbers. That one always needs a reason, because the requirement follows
     * the path the requester took rather than the catalogue: pinning it to the
     * largest published spec would mean an operator adding a larger one
     * silently switches the requirement off.</p>
     */
    /**
     * 사양을 직접 적는 신청의 바닥값.
     *
     * <p>**콘솔의 같은 상수와 맞춰야 한다**(`vm-wizard.tsx`의 `CUSTOM_BASE`). 화면은 이
     * 값에서 출발해 늘릴 축만 켜게 하고, 서버는 이 값을 넘을 때만 사유를 요구한다. 두
     * 값이 어긋나면 화면이 사유를 묻지 않은 신청이 422로 튕기거나, 그 반대가 된다.
     * 카탈로그 행이 아니라 고정 상수인 것이 요점이다 — 관리자가 사양을 추가해도
     * 판정이 움직이지 않는다.</p>
     */
    static final int CUSTOM_BASE_VCPU = 1;
    static final int CUSTOM_BASE_MEMORY_MB = 1024;
    static final int CUSTOM_BASE_DISK_GB = 32;

    private static void validateSpec(CreateVmRequestSpec spec, OsImage image,
            @Nullable VmFlavor flavor, List<FieldValidationError> errors) {
        if (spec.reqDiskGb() < image.getMinDiskGb()) {
            errors.add(new FieldValidationError("vm.reqDiskGb",
                    "이 OS의 최소 디스크 크기는 " + image.getMinDiskGb() + "GiB입니다."));
        }
        if (Texts.blankToNull(spec.specReason()) != null) {
            return;
        }
        // 규칙이 사용자가 고른 경로를 따른다: 준비된 사양을 골랐으면 그것을 넘을 때,
        // 직접 적었으면 바닥값을 넘을 때 사유가 필요하다. 카탈로그가 판정 기준이 되면
        // 관리자가 더 큰 사양을 하나 만드는 것만으로 사유 요구가 조용히 사라진다.
        //
        // **직접 적었다는 것만으로는 검토 대상이 아니다.** 바닥값은 어느 프리셋보다도
        // 작으므로, 그대로 낸 신청은 준비된 어느 사양보다 적게 달라는 신청이다. 거기에
        // 사유를 요구하면 작게 쓰겠다는 사람에게만 문턱을 세우는 셈이 된다.
        if (flavor == null) {
            int baseDisk = Math.max(CUSTOM_BASE_DISK_GB, image.getMinDiskGb());
            if (spec.reqVcpu() > CUSTOM_BASE_VCPU
                    || spec.reqMemoryMb() > CUSTOM_BASE_MEMORY_MB
                    || spec.reqDiskGb() > baseDisk) {
                errors.add(new FieldValidationError("vm.specReason",
                        "기본값보다 큰 사양을 직접 적을 때는 사유를 입력해야 합니다."));
            }
        } else if (spec.reqVcpu() > flavor.getVcpu()
                || spec.reqMemoryMb() > flavor.getMemoryMb()
                // 고른 사양의 디스크가 OS 최소치보다 작으면 그 최소치가 바닥이다.
                // 올리지 않으면 OS가 요구하는 크기를 맞춘 것만으로 사유를 요구하게 되고,
                // 화면에는 그 사양에서 고칠 칸이 없어 되돌아갈 곳 없는 422가 된다.
                || spec.reqDiskGb() > Math.max(flavor.getDiskGb(), image.getMinDiskGb())) {
            errors.add(new FieldValidationError("vm.specReason",
                    "선택한 사양을 초과하는 신청에는 사유를 입력해야 합니다."));
        }
    }

    /**
     * 어느 노드도 담을 수 없는 사양인지.
     *
     * <p>배치는 메모리를 경성 필터로 쓰므로, 물리적으로 수용 불가능한 사양이 승인되면
     * VM 이 배치 단계에서 오류로 주차한다. 그것을 신청 시점에 돌려보낸다. 정책 상한이
     * 아니라 물리 상한이라 설정 키가 필요 없고, 노드가 늘면 저절로 넓어진다.</p>
     *
     * <p>디스크는 씬 프로비저닝이라 <em>합계</em>가 용량을 넘는 것이 정상이다. 그래서
     * 여기서 보는 것은 요청 한 건이 어느 노드의 풀보다 큰 경우뿐이고, 용량을 재지 않은
     * 노드({@code diskCapacityGb == null})는 그 판정에서 빠진다.</p>
     */
    private void validateFitsSomeNode(CreateVmRequestSpec spec,
            List<FieldValidationError> errors) {
        List<Node> nodes = nodeRepository.findByStatusOrderByIdAsc(NodeStatus.ACTIVE);
        if (nodes.isEmpty()) {
            return;
        }
        long maxMemoryMb = nodes.stream().mapToLong(Node::getMemoryMb).max().orElse(0);
        if (spec.reqMemoryMb() > maxMemoryMb) {
            errors.add(new FieldValidationError("vm.reqMemoryMb",
                    "요청한 메모리를 수용할 수 있는 노드가 없습니다. 가장 큰 노드의 메모리는 "
                            + maxMemoryMb + "MiB입니다."));
        }
        OptionalLong maxDiskGb = nodes.stream()
                .map(Node::getDiskCapacityGb)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .max();
        if (maxDiskGb.isPresent() && spec.reqDiskGb() > maxDiskGb.getAsLong()) {
            errors.add(new FieldValidationError("vm.reqDiskGb",
                    "요청한 디스크를 수용할 수 있는 노드가 없습니다. 가장 큰 노드의 디스크는 "
                            + maxDiskGb.getAsLong() + "GiB입니다."));
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
     *
     * <p>The generated name goes through the same policy a user-chosen one
     * does. It is derived from free text, so it can land on a reserved word or
     * a profanity by accident, and a hostname is an SSH name and a subdomain
     * default -- exactly what that list exists to protect.
     */
    private String generateHostname(String seed, long workspaceId) {
        // A seed the policy refuses cannot be rescued by another suffix, so it is
        // replaced once rather than retried: the profanity list matches on
        // substrings, and a display name like "XXX 프로젝트" would otherwise burn
        // every attempt and fail the approval with a 500.
        String effective = policyAccepts(seed + "-aaaa") ? seed : VmSlugPolicy.fallbackSeed(workspaceId);
        for (int attempt = 0; attempt < HOSTNAME_MAX_ATTEMPTS; attempt++) {
            StringBuilder suffix = new StringBuilder(HOSTNAME_SUFFIX_LENGTH);
            for (int i = 0; i < HOSTNAME_SUFFIX_LENGTH; i++) {
                suffix.append(HOSTNAME_SUFFIX_ALPHABET[random.nextInt(HOSTNAME_SUFFIX_ALPHABET.length)]);
            }
            String hostname = effective + "-" + suffix;
            if (policyAccepts(hostname) && !vmRepository.existsByHostname(hostname)) {
                return hostname;
            }
        }
        throw new IllegalStateException("Could not generate a unique hostname from seed " + effective);
    }
}
