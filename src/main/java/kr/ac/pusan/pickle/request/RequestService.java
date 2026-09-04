package kr.ac.pusan.pickle.request;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.audit.AuditIds;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.request.period.RequestPeriodPreset;
import kr.ac.pusan.pickle.request.period.RequestPeriodPresetRepository;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.OrgStatus;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.request.dto.CreateRequestRequest;
import kr.ac.pusan.pickle.request.dto.RequestDetailResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
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
public class RequestService {

    /**
     * 직접 적는 종료일의 상한. 기간 항목이 덮지 못하는 경우를 위한 안전장치이고,
     * 이보다 긴 기간은 항목으로 만들어 주는 것이 맞다.
     */
    private static final int MAX_CUSTOM_PERIOD_YEARS = 2;

    private final RequestRepository requestRepository;
    private final RequestAssembler assembler;
    private final Map<ResourceType, RequestTypeHandler> handlers;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final OrgRepository orgRepository;
    private final AuditService auditService;
    private final AuditIds auditIds;
    private final NotificationService notificationService;
    private final RequestPeriodPresetRepository periodPresetRepository;
    private final Clock clock;

    public RequestService(RequestRepository requestRepository, RequestAssembler assembler,
            List<RequestTypeHandler> handlers, WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository, OrgRepository orgRepository,
            AuditService auditService, AuditIds auditIds, NotificationService notificationService,
            RequestPeriodPresetRepository periodPresetRepository, Clock clock) {
        this.requestRepository = requestRepository;
        this.assembler = assembler;
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(RequestTypeHandler::type, Function.identity()));
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.orgRepository = orgRepository;
        this.auditService = auditService;
        this.auditIds = auditIds;
        this.notificationService = notificationService;
        this.periodPresetRepository = periodPresetRepository;
        this.clock = clock;
    }

    /** The handler for a type, or a validation failure naming the unknown type. */
    private RequestTypeHandler handlerFor(ResourceType type) {
        RequestTypeHandler handler = handlers.get(type);
        if (handler == null) {
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("type", "아직 신청할 수 없는 리소스 종류입니다.")));
        }
        return handler;
    }

    @Transactional
    public RequestDetailResponse create(AuthenticatedUser actor, CreateRequestRequest form, String ip) {
        RequestTypeHandler handler = handlerFor(form.type());
        // A soft-deleted workspace cannot receive new requests.
        Workspace workspace = workspaceRepository.findByPublicIdAndDeletedAtIsNull(form.workspaceId())
                .orElseThrow(() -> notFound("해당 워크스페이스가 존재하지 않습니다."));
        // Any member may ask. The rung that used to gate this was really about
        // reaching VMs, which is now the access list's business, and asking is
        // not the step that costs anything — approval is.
        workspaceMemberRepository.findByWorkspaceIdAndUserId(workspace.getId(), actor.id())
                .orElseThrow(RequestService::notWorkspaceMember);

        Org org = orgRepository.findByPublicId(form.orgId())
                .orElseThrow(() -> notFound("해당 기관이 존재하지 않습니다."));
        if (org.getStatus() != OrgStatus.ACTIVE) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("orgId",
                    "비활성화된 기관에는 신청할 수 없습니다.")));
        }

        List<FieldValidationError> errors = new ArrayList<>();
        ResolvedPeriod period = resolvePeriod(form, errors);
        handler.validateCreate(form, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }

        Request saved = requestRepository.save(new Request(form.type(), workspace.getId(), org.getId(),
                actor.id(), form.purpose().strip(),
                Texts.blankToNull(form.extraNote()), period.endDate(), period.presetId(),
                form.displayName().strip()));
        handler.saveDetail(saved, form);

        Map<String, Object> auditArgs = new LinkedHashMap<>();
        auditArgs.put("type", form.type().name());
        auditArgs.put("workspaceId", workspace.getPublicId());
        auditArgs.put("orgId", org.getPublicId());
        auditArgs.putAll(handler.submitAuditArgs(saved));
        auditService.record(actor.id(), actor.role().name(), AuditService.REQUEST_CREATE,
                "request", saved.getPublicId(), auditArgs, ip);
        // In-tx inserts: the notices exist iff the request row committed.
        notificationService.publish(actor.id(), NotificationEvent.REQUEST_SUBMITTED,
                Map.of("requestId", saved.getPublicId(), "workspaceName", workspace.getName(),
                        "purpose", saved.getPurpose(), "type", form.type().name()), null);
        notificationService.publish(
                notificationService.orgAdminIds(org.getId()).stream()
                        .filter(adminId -> !adminId.equals(actor.id())).toList(),
                NotificationEvent.REQUEST_SUBMITTED,
                Map.of("requestId", saved.getPublicId(), "workspaceName", workspace.getName(),
                        "purpose", saved.getPurpose(), "type", form.type().name(), "admin", true), null);
        return assembler.toDetail(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<RequestDetailResponse> list(AuthenticatedUser actor, RequestStatus status,
            ResourceType type, UUID workspaceId, int page, int size) {
        Specification<Request> spec;
        if (workspaceId != null) {
            // Unknown id and one I am not a member of answer the same 403, so a
            // workspace's existence stays private here as it did before.
            Long scopedWorkspaceId = workspaceRepository.findByPublicId(workspaceId)
                    .map(Workspace::getId).orElse(null);
            if (scopedWorkspaceId == null || workspaceMemberRepository
                    .findByWorkspaceIdAndUserId(scopedWorkspaceId, actor.id()).isEmpty()) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                        "접근 권한이 없습니다", "해당 워크스페이스의 신청을 조회할 권한이 없습니다.");
            }
            spec = RequestSpecs.workspace(scopedWorkspaceId);
        } else {
            spec = RequestSpecs.visibleTo(actor.id(), myWorkspaceIds(actor));
        }
        if (status != null) {
            spec = spec.and(RequestSpecs.status(status));
        }
        if (type != null) {
            spec = spec.and(RequestSpecs.type(type));
        }
        Page<Request> result = requestRepository.findAll(spec, newestFirst(page, size));
        return PageResponse.of(assembler.toDetails(result.getContent()), result);
    }

    @Transactional(readOnly = true)
    public RequestDetailResponse get(AuthenticatedUser actor, UUID requestId) {
        Request request = requestRepository.findByPublicId(requestId)
                .orElseThrow(() -> notFound("해당 신청이 존재하지 않습니다."));
        boolean participant = request.getRequesterId().equals(actor.id())
                || workspaceMemberRepository.findByWorkspaceIdAndUserId(request.getWorkspaceId(), actor.id()).isPresent();
        if (!participant) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "신청자 또는 워크스페이스 구성원만 조회할 수 있습니다.");
        }
        return assembler.toDetail(request);
    }

    @Transactional
    public RequestDetailResponse cancel(AuthenticatedUser actor, UUID requestId, String ip) {
        Request request = requestRepository.findWithLockByPublicId(requestId)
                .orElseThrow(() -> notFound("해당 신청이 존재하지 않습니다."));
        boolean requester = request.getRequesterId().equals(actor.id());
        boolean workspaceOwner = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(request.getWorkspaceId(), actor.id())
                .map(WorkspaceMember::getRole)
                .filter(role -> role == WorkspaceMemberRole.OWNER)
                .isPresent();
        if (!requester && !workspaceOwner) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "신청자 본인 또는 워크스페이스 소유자(OWNER)만 취소할 수 있습니다.");
        }
        if (request.getStatus() != RequestStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.REQUEST_ALREADY_DECIDED,
                    "이미 처리된 신청입니다", "이미 승인 또는 반려된 신청은 취소할 수 없습니다.");
        }
        request.setStatus(RequestStatus.CANCELED);
        auditService.record(actor.id(), actor.role().name(), AuditService.REQUEST_CANCEL,
                "request", request.getPublicId(),
                Map.of("workspaceId", auditIds.workspace(request.getWorkspaceId())), ip);
        return assembler.toDetail(request);
    }

    private List<Long> myWorkspaceIds(AuthenticatedUser actor) {
        List<Long> workspaceIds = workspaceMemberRepository.findWithWorkspaceByUserId(actor.id()).stream()
                .map(m -> m.getWorkspace().getId())
                .toList();
        // JPQL "in ()" is invalid — a user without any membership sees only
        // their own requests, so pass an id that can never match.
        return workspaceIds.isEmpty() ? List.of(-1L) : workspaceIds;
    }

    private static Pageable newestFirst(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    }

    /**
     * 요청한 사용 기간. 종료일은 고른 항목에서 복사하거나 직접 적은 값이고,
     * {@code presetId}는 그 값이 어디서 왔는지만 기록한다.
     */
    private record ResolvedPeriod(@Nullable LocalDate endDate, @Nullable Long presetId) {
    }

    /**
     * 기간 항목을 고른 경우와 직접 적은 경우를 한 값으로 정리한다.
     *
     * <p>종료일을 필수로 두면서 달력만 주면 모든 날짜를 여기서 검사해야 하는데, 실제 기간은
     * 대개 운영자가 이미 아는 학기나 방학이다. 그래서 고르는 쪽을 기본 경로로 두고 직접
     * 입력을 남겨 둔다. 무기한은 카탈로그가 아니라 {@code reqIndefinite}가 싣는다.</p>
     *
     * <p>고른 항목의 종료일은 신청 행에 복사한다. 다음 학기에 운영자가 항목의 날짜를
     * 고쳐도 이미 낸 신청의 기간이 따라 움직이면 안 되기 때문이다.</p>
     */
    private ResolvedPeriod resolvePeriod(CreateRequestRequest form,
            List<FieldValidationError> errors) {
        // 무기한은 값이 없는 상태가 아니라 하나의 값이다. 빠뜨린 종료일과 겹치지
        // 않도록 다른 두 필드와 함께 오는 것을 막는다.
        if (Boolean.TRUE.equals(form.reqIndefinite())) {
            if (form.periodPresetId() != null || form.reqEndDate() != null) {
                errors.add(new FieldValidationError("reqIndefinite",
                        "무기한으로 신청할 때는 기간이나 종료일을 함께 보내지 않습니다."));
            }
            return new ResolvedPeriod(null, null);
        }
        if (form.periodPresetId() != null) {
            if (form.reqEndDate() != null) {
                errors.add(new FieldValidationError("reqEndDate",
                        "기간을 골랐을 때는 종료일을 따로 보내지 않습니다."));
                return new ResolvedPeriod(null, null);
            }
            RequestPeriodPreset preset = periodPresetRepository
                    .findByPublicId(form.periodPresetId()).orElse(null);
            if (preset == null) {
                throw notFound("해당 사용 기간이 존재하지 않습니다.");
            }
            if (!preset.isOfferableOn(ClockConfig.todayKst(clock))) {
                errors.add(new FieldValidationError("periodPresetId",
                        "더 이상 신청할 수 없는 사용 기간입니다."));
                return new ResolvedPeriod(null, null);
            }
            return new ResolvedPeriod(preset.getEndDate(), preset.getId());
        }

        LocalDate endDate = form.reqEndDate();
        if (endDate == null) {
            errors.add(new FieldValidationError("reqEndDate",
                    "사용 종료일을 정해 주세요. 끝나지 않는 기간이 필요하면 무기한을 고릅니다."));
            return new ResolvedPeriod(null, null);
        }
        LocalDate today = ClockConfig.todayKst(clock);
        if (endDate.isBefore(today)) {
            errors.add(new FieldValidationError("reqEndDate", "종료일은 오늘 이후여야 합니다."));
        } else if (endDate.isAfter(today.plusYears(MAX_CUSTOM_PERIOD_YEARS))) {
            errors.add(new FieldValidationError("reqEndDate",
                    "직접 적는 종료일은 " + MAX_CUSTOM_PERIOD_YEARS
                            + "년 이내여야 합니다. 더 긴 기간이 필요하면 사용 목적에 적어 주세요."));
        }
        return new ResolvedPeriod(endDate, null);
    }

    private static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", detail);
    }

    /**
     * The only standing this path still asks for is membership, and it is not
     * about VMs: any type of request travels through here, and what the refusal
     * has to tell the caller is that they are outside the workspace.
     */
    private static ApiException notWorkspaceMember() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.WORKSPACE_MEMBERSHIP_REQUIRED,
                "워크스페이스 구성원이 아닙니다",
                "이 워크스페이스의 구성원만 신청할 수 있습니다. 워크스페이스 소유자에게 구성원 추가를 요청해 주세요.");
    }
}
