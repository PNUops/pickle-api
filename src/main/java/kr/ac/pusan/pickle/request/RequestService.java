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
import kr.ac.pusan.pickle.admin.dto.ApproveRequestRequest;
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
    private final RequestApproval requestApproval;
    private final Map<ResourceType, RequestTypeHandler> handlers;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final OrgRepository orgRepository;
    private final AuditService auditService;
    private final AuditIds auditIds;
    private final NotificationService notificationService;
    private final RequestPeriodPresetRepository periodPresetRepository;
    private final Clock clock;
    private final RequestRecipientService recipientService;
    private final kr.ac.pusan.pickle.admin.ApprovalService approvalService;
    private final kr.ac.pusan.pickle.admin.AdminWorkspaceQueryService adminWorkspaceQueryService;

    public RequestService(RequestRepository requestRepository, RequestAssembler assembler, RequestApproval requestApproval,
            List<RequestTypeHandler> handlers, WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository, OrgRepository orgRepository,
            AuditService auditService, AuditIds auditIds, NotificationService notificationService,
            RequestPeriodPresetRepository periodPresetRepository, Clock clock,
            RequestRecipientService recipientService,
            kr.ac.pusan.pickle.admin.ApprovalService approvalService,
            kr.ac.pusan.pickle.admin.AdminWorkspaceQueryService adminWorkspaceQueryService) {
        this.adminWorkspaceQueryService = adminWorkspaceQueryService;
        this.recipientService = recipientService;
        this.approvalService = approvalService;
        this.requestRepository = requestRepository;
        this.assembler = assembler;
        this.requestApproval = requestApproval;
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
        //
        // One exception: an approver of the organisation may file into a
        // workspace they are not in, but only when deciding it in the same step.
        // Filing a request somebody else then has to decide would let anyone
        // with an admin role put work into any workspace's queue.
        WorkspaceMember membership = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(workspace.getId(), actor.id()).orElse(null);
        if (membership == null && form.approval() == null) {
            throw notWorkspaceMember();
        }
        if (membership == null) {
            // Any live workspace, PERSONAL included (operator decision,
            // 2026-09-28): the same reach the admin workspace and invitation
            // reads have. What the approver still needs is the right to decide
            // for the request's organisation, checked below.
            adminWorkspaceQueryService.requireOperated(actor, workspace.getPublicId());
        }
        // Checked before the form's contents so a refusal does not first teach
        // the caller which of their recipients are members. Only the kinds that
        // take the organisation from the form can be answered this early; the
        // rest are answered once the organisation is known, below.
        if (!handler.derivesOrgId()) {
            Long formOrgId = form.orgId() == null ? null
                    : orgRepository.findByPublicId(form.orgId()).map(Org::getId).orElse(null);
            requireMaySubmitAs(form, actor, membership, formOrgId);
        }

        List<FieldValidationError> errors = new ArrayList<>();
        // An approver outside the workspace files only for its members: a
        // request for themselves would make them the owner of a resource in a
        // workspace they do not belong to, which approval then refuses.
        if (membership == null && !form.hasRecipients()) {
            errors.add(new FieldValidationError("recipients",
                    "워크스페이스 구성원이 아니면 대상자를 지정해야 합니다. 본인 리소스는 구성원인 워크스페이스에서만 신청할 수 있습니다."));
        }
        List<RequestRecipientService.Resolved> recipients = List.of();
        if (form.hasRecipients()) {
            if (!handler.supportsRecipients()) {
                errors.add(new FieldValidationError("recipients",
                        "대상자는 VM과 LLM API 키 신청에만 지정할 수 있습니다."));
            } else {
                // Every recipient's VM is named from the display name with a
                // random suffix; one chosen name cannot be several hosts.
                if (form.vm() != null && Texts.blankToNull(form.vm().desiredSlug()) != null) {
                    errors.add(new FieldValidationError("vm.desiredSlug",
                            "대상자가 여러 명인 신청에는 호스트 이름을 정할 수 없습니다."));
                }
                recipients = recipientService.resolve(workspace, form.recipients(), errors);
            }
        }
        // Asked here rather than by an annotation, because whether the field is
        // required is the kind's answer. Asked *before* the kind validates so
        // that a form missing this and something else gets told both at once:
        // the annotation it replaces reported alongside every other missing
        // field, and answering in two round trips instead would be a step back
        // for anything that is not this platform's own console.
        if (!handler.derivesOrgId() && form.orgId() == null) {
            errors.add(new FieldValidationError("orgId", "기관(orgId)을 지정해 주세요."));
        }
        // A kind whose resource carries its own deadline is not asked for a
        // period and is not refused for leaving it out. A period that arrived
        // anyway is dropped rather than stored: keeping it would show the
        // applicant a date on their request that nothing in the system honours,
        // beside the one that actually ends the resource.
        ResolvedPeriod period = handler.ownsItsOwnLifetime()
                ? new ResolvedPeriod(null, null)
                : resolvePeriod(form, errors);
        handler.validateCreate(form, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }

        // After the kind's own validation, not before it. The organisation can
        // come from what is being asked for, and working it out from a root
        // domain that turns out not to exist would answer with that failure
        // instead of the field error the applicant needs to see.
        Org org = resolveOrg(handler, form);
        requireMaySubmitAs(form, actor, membership, org.getId());
        Request saved = requestRepository.save(new Request(form.type(), workspace.getId(),
                org.getId(),
                actor.id(), form.purpose().strip(),
                Texts.blankToNull(form.extraNote()), period.endDate(), period.presetId(),
                form.displayName().strip()));
        handler.saveDetail(saved, form);
        // Recipients named by 학번 become members or invitees here, after every
        // check on the form. This happens at submission, also for a request
        // that is later rejected or canceled: the roster is the class,
        // whatever becomes of this one request for it.
        RequestRecipientService.Placed placed =
                recipientService.placeStudentNos(actor, workspace, saved, recipients, ip);
        recipients = placed.recipients();
        recipientService.saveAtSubmission(saved, recipients);

        RequestDetailResponse response;

        // An approver deciding what they submit, in this transaction and
        // through the approve endpoint's own code. Anything that refuses the
        // approval rolls the submission back with it.
        if (form.approval() != null) {
            response = submitAndApprove(actor, saved, handler, workspace, org.getPublicId(),
                    form.approval(), recipients.size(), ip);
        } else if (handler.isAutoApproved(form)) {
            // A kind whose policy issues without a reviewer is approved here,
            // in this transaction, through the same code an approving reviewer
            // runs. Deciding it later — a sweep, a job — would leave a window
            // in which the applicant is looking at a request nobody will ever
            // act on.
            response = autoApprove(actor, saved, handler, workspace, org.getPublicId(), ip);
        } else {
            response = submitForReview(actor, form, saved, handler, workspace, org, recipients.size(), ip);
        }
        // Last, once nothing else can refuse the submission. The budget is
        // charged in its own transaction and stays spent if this one rolls
        // back, so charging any earlier would bill a refused approval; a
        // refusal here rolls back every placement above with the rest.
        recipientService.chargePlacements(actor, placed.written());
        return response;
    }

    private RequestDetailResponse submitForReview(AuthenticatedUser actor, CreateRequestRequest form,
            Request saved, RequestTypeHandler handler, Workspace workspace, Org org, int recipientCount,
            String ip) {

        Map<String, Object> auditArgs = new LinkedHashMap<>();
        auditArgs.put("type", form.type().name());
        auditArgs.put("workspaceId", workspace.getPublicId());
        auditArgs.put("orgId", org.getPublicId());
        auditArgs.putAll(handler.submitAuditArgs(saved));
        if (recipientCount > 0) {
            auditArgs.put("recipients", recipientCount);
        }
        auditService.record(actor.id(), actor.role().name(), AuditService.REQUEST_CREATE,
                "request", saved.getPublicId(), auditArgs, ip);
        // In-tx inserts: the notices exist iff the request row committed.
        notificationService.publishRequestSubmitted(saved.getId(), saved.getPublicId(),
                org.getPublicId(), actor.id(),
                Map.of("requestId", saved.getPublicId(), "workspaceName", workspace.getName(),
                        "purpose", saved.getPurpose(), "type", form.type().name()));
        return assembler.toDetail(saved, actor);
    }

    /**
     * Who may send what. Recipients are for a workspace owner or an approver
     * of the organisation; a same-step approval is for an approver only.
     */
    private static void requireMaySubmitAs(CreateRequestRequest form, AuthenticatedUser actor,
            @Nullable WorkspaceMember membership, @Nullable Long orgId) {
        if (form.approval() != null && !RequestApprovers.mayApprove(actor, orgId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "이 기관의 신청을 승인할 수 있는 관리자만 제출과 동시에 승인할 수 있습니다.");
        }
        if (form.hasRecipients() && !RecipientNamers.mayName(actor, membership, orgId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.REQUEST_RECIPIENTS_FORBIDDEN,
                    "대상자를 지정할 권한이 없습니다",
                    "다른 사람을 대상자로 지정하는 신청은 워크스페이스 소유자나 이 기관의 신청을 승인할 수 있는 관리자만 낼 수 있습니다.");
        }
    }

    /**
     * Files and approves in one step. Both audit rows are written, after
     * commit, so a refused approval leaves no record of a submission that did
     * not happen either. The organisation's approvers are not told: the one
     * who would act on it already has.
     */
    private RequestDetailResponse submitAndApprove(AuthenticatedUser actor, Request saved,
            RequestTypeHandler handler, Workspace workspace, UUID orgPublicId,
            ApproveRequestRequest approval, int recipientCount, String ip) {
        Map<String, Object> submitArgs = new LinkedHashMap<>();
        submitArgs.put("type", saved.getResourceType().name());
        submitArgs.put("workspaceId", workspace.getPublicId());
        submitArgs.put("orgId", orgPublicId);
        submitArgs.putAll(handler.submitAuditArgs(saved));
        submitArgs.put("submittedByReviewer", true);
        if (recipientCount > 0) {
            submitArgs.put("recipients", recipientCount);
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.REQUEST_CREATE,
                "request", saved.getPublicId(), submitArgs, ip);
        approvalService.approveOnSubmission(actor, saved, approval, ip);
        return assembler.toDetail(saved, actor);
    }

    /**
     * Approves a submission the policy says needs no reviewer.
     *
     * <p>The record says a decision was made and that no person made it. The
     * alternative — leaving {@code request_reviews} empty — would show the
     * applicant a request that is approved with nothing saying when or by
     * what, and would take the approved-request guard out of the loop for a
     * whole kind.</p>
     *
     * <p>One notice, not two. The submitted-and-then-approved pair is a story
     * about waiting, and nobody waited; the organisation's administrators are
     * not told at all, because there is nothing for them to do about a request
     * that is already finished.</p>
     */
    private RequestDetailResponse autoApprove(AuthenticatedUser actor, Request saved,
            RequestTypeHandler handler, Workspace workspace, UUID orgPublicId, String ip) {
        RequestTypeHandler.Materialized created = requestApproval.apply(saved, handler,
                new ApproveRequestRequest(null, null, null, null, null, null), null, actor);

        // Both rows, not one. The submission happened — somebody asked for this
        // and the audit is where "was it ever asked for" is answered — and the
        // approval happened too. Writing only the approval leaves a request
        // that, to anyone filtering by request.create, never existed.
        Map<String, Object> submitArgs = new LinkedHashMap<>();
        submitArgs.put("type", saved.getResourceType().name());
        submitArgs.put("workspaceId", workspace.getPublicId());
        submitArgs.put("orgId", orgPublicId);
        submitArgs.putAll(handler.submitAuditArgs(saved));
        auditService.record(actor.id(), actor.role().name(), AuditService.REQUEST_CREATE,
                "request", saved.getPublicId(), submitArgs, ip);

        Map<String, Object> auditArgs = new LinkedHashMap<>();
        auditArgs.put("type", saved.getResourceType().name());
        auditArgs.put("workspaceId", workspace.getPublicId());
        // The actor on this row is the applicant, because they are who acted.
        // Without this flag an audit reader sees somebody approving their own
        // request; with it the row says a policy did.
        auditArgs.put("automatic", true);
        auditArgs.putAll(created.auditArgs());
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.REQUEST_APPROVE, "request", saved.getPublicId(), auditArgs, ip);

        Map<String, Object> notifyArgs = new LinkedHashMap<>();
        notifyArgs.put("requestId", saved.getPublicId());
        notifyArgs.put("type", saved.getResourceType().name());
        notifyArgs.put("resourceName", created.resourceName());
        notifyArgs.put("automatic", true);
        notifyArgs.putAll(created.notificationArgs());
        notificationService.publish(actor.id(), NotificationEvent.REQUEST_APPROVED, notifyArgs, null);
        return assembler.toDetail(saved, actor);
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
            spec = RequestSpecs.filedBy(actor.id());
        }
        if (status != null) {
            spec = spec.and(RequestSpecs.status(status));
        }
        if (type != null) {
            spec = spec.and(RequestSpecs.type(type));
        }
        Page<Request> result = requestRepository.findAll(spec, newestFirst(page, size));
        return PageResponse.of(assembler.toDetails(result.getContent(), actor), result);
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
        return assembler.toDetail(request, actor);
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
        recipientService.closeUndecided(request);
        auditService.record(actor.id(), actor.role().name(), AuditService.REQUEST_CANCEL,
                "request", request.getPublicId(),
                Map.of("workspaceId", auditIds.workspace(request.getWorkspaceId())), ip);
        return assembler.toDetail(request, actor);
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
    /**
     * Whose the resource will be.
     *
     * <p>Two sources, and the kind's own answer wins. A kind whose form already
     * decides the organisation — a domain takes it from the root it is asked
     * under — is not asked again, because a form that carries both can carry
     * two different answers and nothing downstream could tell which was meant.
     * Every other kind is asked, and for those the field is still required.</p>
     */
    private Org resolveOrg(RequestTypeHandler handler, CreateRequestRequest form) {
        Long owned = handler.owningOrgId(form).orElse(null);
        // Reached only after the missing-field check above, so a form with no
        // organisation and no kind to derive one never gets this far.
        Org org = (owned != null ? orgRepository.findById(owned)
                : orgRepository.findByPublicId(form.orgId()))
                .orElseThrow(() -> notFound("해당 기관이 존재하지 않습니다."));
        if (org.getStatus() != OrgStatus.ACTIVE) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("orgId",
                    "비활성화된 기관에는 신청할 수 없습니다.")));
        }
        return org;
    }

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
