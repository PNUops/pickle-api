package kr.ac.pusan.pickle.vmrequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.group.Group;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.group.GroupRepository;
import kr.ac.pusan.pickle.inventory.TemplateStatus;
import kr.ac.pusan.pickle.inventory.VmFlavor;
import kr.ac.pusan.pickle.inventory.VmFlavorRepository;
import kr.ac.pusan.pickle.inventory.VmTemplate;
import kr.ac.pusan.pickle.inventory.VmTemplateRepository;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.OrgStatus;
import kr.ac.pusan.pickle.publishing.SubdomainPolicy;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vmrequest.dto.CreateVmRequestRequest;
import kr.ac.pusan.pickle.vmrequest.dto.VmRequestDetailResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User side of the VM request flow (contract tag {@code vm-requests}):
 * submission with the contract's cross-field rules, visibility-scoped listing
 * and cancellation. Approval/rejection live in the admin ApprovalService.
 */
@Service
public class VmRequestService {

    private final VmRequestRepository requestRepository;
    private final VmRequestAssembler assembler;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final OrgRepository orgRepository;
    private final VmTemplateRepository templateRepository;
    private final VmFlavorRepository flavorRepository;
    private final SettingsService settingsService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final VmRepository vmRepository;
    private final VmSlugPolicy slugPolicy;
    private final SubdomainPolicy subdomainPolicy;

    public VmRequestService(VmRequestRepository requestRepository, VmRequestAssembler assembler,
            GroupRepository groupRepository, GroupMemberRepository groupMemberRepository,
            OrgRepository orgRepository, VmTemplateRepository templateRepository,
            VmFlavorRepository flavorRepository,
            SettingsService settingsService, AuditService auditService,
            NotificationService notificationService, VmRepository vmRepository,
            VmSlugPolicy slugPolicy, SubdomainPolicy subdomainPolicy) {
        this.requestRepository = requestRepository;
        this.assembler = assembler;
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.orgRepository = orgRepository;
        this.templateRepository = templateRepository;
        this.flavorRepository = flavorRepository;
        this.settingsService = settingsService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.vmRepository = vmRepository;
        this.slugPolicy = slugPolicy;
        this.subdomainPolicy = subdomainPolicy;
    }

    @Transactional
    public VmRequestDetailResponse create(AuthenticatedUser actor, CreateVmRequestRequest request, String ip) {
        // A soft-deleted group cannot receive new VM requests.
        Group group = groupRepository.findByIdAndDeletedAtIsNull(request.groupId())
                .orElseThrow(() -> notFound("해당 그룹이 존재하지 않습니다."));
        GroupMemberRole role = groupMemberRepository
                .findByGroupIdAndUserId(group.getId(), actor.id())
                .map(GroupMember::getRole)
                .orElseThrow(VmRequestService::requestRoleInsufficient);
        if (role != GroupMemberRole.OWNER && role != GroupMemberRole.EDITOR) {
            throw requestRoleInsufficient();
        }

        Org org = orgRepository.findById(request.orgId())
                .orElseThrow(() -> notFound("해당 기관이 존재하지 않습니다."));
        if (org.getStatus() != OrgStatus.ACTIVE) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("orgId",
                    "비활성화된 기관에는 신청할 수 없습니다.")));
        }
        VmTemplate template = templateRepository.findById(request.templateId())
                .orElseThrow(() -> notFound("해당 템플릿이 존재하지 않습니다."));
        VmFlavor flavor = flavorRepository.findById(request.flavorId())
                .orElseThrow(() -> notFound("해당 사양 프리셋이 존재하지 않습니다."));

        List<FieldValidationError> errors = new ArrayList<>();
        boolean axesActive = true;
        if (template.getStatus() != TemplateStatus.ACTIVE) {
            errors.add(new FieldValidationError("templateId", "더 이상 선택할 수 없는 템플릿입니다."));
            axesActive = false;
        }
        if (flavor.getStatus() != TemplateStatus.ACTIVE) {
            errors.add(new FieldValidationError("flavorId", "더 이상 선택할 수 없는 사양 프리셋입니다."));
            axesActive = false;
        }
        if (axesActive) {
            validateSpec(request, template, flavor, errors);
        }
        validateDates(request, errors);
        validateDomains(request, errors);
        String desiredSlug = Texts.blankToNull(request.desiredSlug());
        validateSlug(desiredSlug, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }

        VmRequest saved = requestRepository.save(new VmRequest(group.getId(), org.getId(), actor.id(),
                template.getId(), flavor.getId(), request.purpose().strip(),
                Texts.blankToNull(request.courseOrProject()), Texts.blankToNull(request.specReason()),
                Texts.blankToNull(request.extraNote()),
                request.reqVcpu(), request.reqMemoryMb(), request.reqDiskGb(),
                request.reqStartDate(), request.reqEndDate(),
                request.desiredSubdomain(), Texts.blankToNull(request.rootDomain()),
                Texts.blankToNull(request.displayName()), desiredSlug));
        auditService.record(actor.id(), actor.role().name(), AuditService.REQUEST_CREATE,
                "vm_request", saved.getId(),
                Map.of("groupId", group.getId(), "orgId", org.getId(), "templateId", template.getId(),
                        "reqVcpu", saved.getReqVcpu(), "reqMemoryMb", saved.getReqMemoryMb(),
                        "reqDiskGb", saved.getReqDiskGb()), ip);
        // In-tx inserts: the notices exist iff the request row committed.
        notificationService.publish(actor.id(), NotificationEvent.REQUEST_SUBMITTED,
                Map.of("requestId", saved.getId(), "groupName", group.getName(),
                        "purpose", saved.getPurpose()), null);
        notificationService.publish(
                notificationService.orgAdminIds(org.getId()).stream()
                        .filter(adminId -> !adminId.equals(actor.id())).toList(),
                NotificationEvent.REQUEST_SUBMITTED,
                Map.of("requestId", saved.getId(), "groupName", group.getName(),
                        "purpose", saved.getPurpose(), "admin", true), null);
        return assembler.toDetail(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<VmRequestDetailResponse> list(AuthenticatedUser actor, VmRequestStatus status,
            Long groupId, int page, int size) {
        Pageable pageable = newestFirst(page, size);
        Page<VmRequest> result;
        if (groupId != null) {
            if (groupMemberRepository.findByGroupIdAndUserId(groupId, actor.id()).isEmpty()) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                        "접근 권한이 없습니다", "해당 그룹의 신청을 조회할 권한이 없습니다.");
            }
            result = status != null
                    ? requestRepository.findByGroupIdAndStatus(groupId, status, pageable)
                    : requestRepository.findByGroupId(groupId, pageable);
        } else {
            List<Long> groupIds = myGroupIds(actor);
            result = status != null
                    ? requestRepository.findVisibleByStatus(actor.id(), groupIds, status, pageable)
                    : requestRepository.findVisible(actor.id(), groupIds, pageable);
        }
        return PageResponse.of(assembler.toDetails(result.getContent()), result);
    }

    @Transactional(readOnly = true)
    public VmRequestDetailResponse get(AuthenticatedUser actor, long requestId) {
        VmRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> notFound("해당 신청이 존재하지 않습니다."));
        boolean participant = request.getRequesterId().equals(actor.id())
                || groupMemberRepository.findByGroupIdAndUserId(request.getGroupId(), actor.id()).isPresent();
        if (!participant) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "신청자 또는 그룹 구성원만 조회할 수 있습니다.");
        }
        return assembler.toDetail(request);
    }

    @Transactional
    public VmRequestDetailResponse cancel(AuthenticatedUser actor, long requestId, String ip) {
        VmRequest request = requestRepository.findWithLockById(requestId)
                .orElseThrow(() -> notFound("해당 신청이 존재하지 않습니다."));
        boolean requester = request.getRequesterId().equals(actor.id());
        Optional<GroupMemberRole> role = groupMemberRepository
                .findByGroupIdAndUserId(request.getGroupId(), actor.id())
                .map(GroupMember::getRole);
        boolean editorOrOwner = role.filter(r -> r == GroupMemberRole.OWNER || r == GroupMemberRole.EDITOR)
                .isPresent();
        if (!requester && !editorOrOwner) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "신청자 본인 또는 그룹 소유자(OWNER)/편집자(EDITOR)만 취소할 수 있습니다.");
        }
        if (request.getStatus() != VmRequestStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.REQUEST_ALREADY_DECIDED,
                    "이미 처리된 신청입니다", "이미 승인 또는 반려된 신청은 취소할 수 없습니다.");
        }
        request.setStatus(VmRequestStatus.CANCELED);
        auditService.record(actor.id(), actor.role().name(), AuditService.REQUEST_CANCEL,
                "vm_request", request.getId(), Map.of("groupId", request.getGroupId()), ip);
        return assembler.toDetail(request);
    }

    private List<Long> myGroupIds(AuthenticatedUser actor) {
        List<Long> groupIds = groupMemberRepository.findWithGroupByUserId(actor.id()).stream()
                .map(m -> m.getGroup().getId())
                .toList();
        // JPQL "in ()" is invalid — a user without any membership sees only
        // their own requests, so pass an id that can never match.
        return groupIds.isEmpty() ? List.of(-1L) : groupIds;
    }

    private static Pageable newestFirst(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    }

    /**
     * Axis-split validation (V58): the hard floor is the OS image's
     * {@code minDiskGb}; the spec-reason baseline is the chosen flavor's
     * values — requesting below a preset stays free, exceeding it needs a
     * reason (same semantics the template defaults carried before the split).
     */
    private void validateSpec(CreateVmRequestRequest request, VmTemplate template, VmFlavor flavor,
            List<FieldValidationError> errors) {
        if (request.reqDiskGb() < template.getMinDiskGb()) {
            errors.add(new FieldValidationError("reqDiskGb",
                    "이 OS의 최소 디스크 크기는 " + template.getMinDiskGb() + "GiB입니다."));
        }
        boolean exceedsFlavor = request.reqVcpu() > flavor.getVcpu()
                || request.reqMemoryMb() > flavor.getMemoryMb()
                || request.reqDiskGb() > flavor.getDiskGb();
        if (exceedsFlavor && Texts.blankToNull(request.specReason()) == null) {
            errors.add(new FieldValidationError("specReason",
                    "선택한 사양 프리셋을 초과하는 신청에는 사유(specReason)를 입력해야 합니다."));
        }
    }

    private static void validateDates(CreateVmRequestRequest request, List<FieldValidationError> errors) {
        if (request.reqStartDate() != null && request.reqEndDate() != null
                && request.reqEndDate().isBefore(request.reqStartDate())) {
            errors.add(new FieldValidationError("reqEndDate", "종료일은 시작일 이후여야 합니다."));
        }
    }

    private void validateDomains(CreateVmRequestRequest request, List<FieldValidationError> errors) {
        // Full subdomain policy — the same reserved/profanity/pattern gate the
        // approval path runs, so a denied label never reaches the review screen.
        subdomainPolicy.validateLabel(request.desiredSubdomain(), "desiredSubdomain", errors);
        String rootDomain = Texts.blankToNull(request.rootDomain());
        if (rootDomain != null
                && !settingsService.stringList(SettingsService.ALLOWED_ROOT_DOMAINS).contains(rootDomain)) {
            errors.add(new FieldValidationError("rootDomain",
                    "'" + rootDomain + "'은(는) 허용된 루트 도메인이 아닙니다."));
        }
        // Contract: duplicates are validated server-side — a pair is taken
        // while another request holds it in a non-terminal state. (Domain
        // issuance re-checks against actually published domains.) A null root
        // resolves to the default root here, so the soft reservation matches
        // what publish would actually claim.
        String effectiveRoot = rootDomain != null ? rootDomain : subdomainPolicy.defaultRootDomain();
        if (request.desiredSubdomain() != null && effectiveRoot != null
                && requestRepository.existsByDesiredSubdomainAndRootDomainAndStatusIn(
                        request.desiredSubdomain(), effectiveRoot,
                        List.of(VmRequestStatus.SUBMITTED, VmRequestStatus.APPROVED))) {
            errors.add(new FieldValidationError("desiredSubdomain",
                    "이미 사용 중이거나 신청된 서브도메인입니다."));
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
        slugPolicy.validateSlug(desiredSlug, "desiredSlug", errors);
        if (errors.size() > before) {
            return;
        }
        if (vmRepository.existsByHostname(desiredSlug)) {
            errors.add(new FieldValidationError("desiredSlug", "이미 사용 중인 호스트명입니다."));
        } else if (requestRepository.existsByDesiredSlugAndStatus(desiredSlug,
                VmRequestStatus.SUBMITTED)) {
            errors.add(new FieldValidationError("desiredSlug", "이미 신청 중인 호스트명입니다."));
        }
    }

    private static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", detail);
    }

    private static ApiException requestRoleInsufficient() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.GROUP_ROLE_INSUFFICIENT,
                "VM을 신청할 권한이 없습니다", "그룹 소유자(OWNER) 또는 편집자(EDITOR)만 VM을 신청할 수 있습니다.");
    }
}
