package kr.ac.pusan.pickle.request;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.request.dto.CreateRequestRecipient;
import kr.ac.pusan.pickle.request.dto.RequestRecipientResponse;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceInvitation;
import kr.ac.pusan.pickle.workspace.WorkspaceInvitationRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceInvitationStatus;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The recipients of a request that makes a resource for each of many people:
 * who they are at submission, where each stands once the request is decided,
 * and what happens to them when an invitee joins or the request ends
 * undecided.
 *
 * <p>Nothing here creates a resource. That is the materializer's work, one
 * recipient per transaction, so that a batch of two hundred cannot hold an
 * approval open or fail as a whole because one of them does.</p>
 *
 * <p>Every method runs inside its caller's transaction.</p>
 */
@Service
public class RequestRecipientService {

    static final String REASON_NOT_MEMBER = "워크스페이스의 활성 구성원이 아닙니다.";
    static final String REASON_INVITATION_CLOSED = "초대가 취소되어 더 이상 가입을 기다리지 않습니다.";
    static final String REASON_DUPLICATE = "같은 사람이 이 신청의 대상자로 이미 들어 있습니다.";
    static final String REASON_EXPIRED = "가입했을 때 사용 기간이 이미 끝나 만들지 않았습니다.";

    /** A recipient as validated at submission: exactly one of the two ids. */
    public record Resolved(@Nullable Long userId, @Nullable Long invitationId) {
    }

    /** How an approval left the recipients, for its audit entry. */
    public record Settlement(int queued, int pendingJoin, int skipped) {
    }

    private final RequestRecipientRepository recipientRepository;
    private final RequestRepository requestRepository;
    private final RequestReviewRepository reviewRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceInvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final RequestRecipientMaterializer materializer;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public RequestRecipientService(RequestRecipientRepository recipientRepository,
            RequestRepository requestRepository, RequestReviewRepository reviewRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            WorkspaceInvitationRepository invitationRepository, UserRepository userRepository,
            RequestRecipientMaterializer materializer, JdbcTemplate jdbcTemplate, Clock clock) {
        this.recipientRepository = recipientRepository;
        this.requestRepository = requestRepository;
        this.reviewRepository = reviewRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.invitationRepository = invitationRepository;
        this.userRepository = userRepository;
        this.materializer = materializer;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    // ── submission ─────────────────────────────────────────────────────────

    /**
     * Checks each named recipient, appending one field error per bad entry so
     * the whole list is reported at once. A user must be an ACTIVE member of
     * the workspace; an invitation must be a PENDING invitation into it.
     */
    public List<Resolved> resolve(Workspace workspace, List<CreateRequestRecipient> entries,
            List<FieldValidationError> errors) {
        List<Resolved> resolved = new ArrayList<>(entries.size());
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            String field = "recipients[" + i + "]";
            CreateRequestRecipient entry = entries.get(i);
            if (entry == null || (entry.userId() == null) == (entry.invitationId() == null)) {
                errors.add(new FieldValidationError(field, "userId와 invitationId 중 하나만 보내 주세요."));
                continue;
            }
            UUID key = entry.userId() != null ? entry.userId() : entry.invitationId();
            if (!seen.add(key)) {
                errors.add(new FieldValidationError(field, "같은 대상자가 두 번 들어 있습니다."));
                continue;
            }
            if (entry.userId() != null) {
                User user = userRepository.findByPublicId(entry.userId()).orElse(null);
                if (user == null || !activeMember(workspace.getId(), user)) {
                    errors.add(new FieldValidationError(field, "이 워크스페이스의 활성 구성원이 아닙니다."));
                    continue;
                }
                resolved.add(new Resolved(user.getId(), null));
            } else {
                WorkspaceInvitation invitation = invitationRepository
                        .findByPublicId(entry.invitationId()).orElse(null);
                if (invitation == null || !invitation.getWorkspaceId().equals(workspace.getId())
                        || invitation.getStatus() != WorkspaceInvitationStatus.PENDING) {
                    errors.add(new FieldValidationError(field, "이 워크스페이스의 대기 중인 초대가 아닙니다."));
                    continue;
                }
                resolved.add(new Resolved(null, invitation.getId()));
            }
        }
        return resolved;
    }

    /**
     * Writes the recipients of a request just saved. The status says where
     * each person stands now; approval evaluates it again, because weeks can
     * pass in between.
     */
    public void saveAtSubmission(Request request, List<Resolved> recipients) {
        for (Resolved recipient : recipients) {
            recipientRepository.save(new RequestRecipient(request.getId(), recipient.userId(),
                    recipient.invitationId(), recipient.userId() != null
                            ? RequestRecipientStatus.QUEUED : RequestRecipientStatus.PENDING_JOIN));
        }
    }

    public boolean hasRecipients(Request request) {
        return recipientRepository.existsByRequestId(request.getId());
    }

    // ── decision ───────────────────────────────────────────────────────────

    /**
     * Places every recipient of a request being approved: a member who is
     * still one is queued, an invitee still invited waits to join, anyone
     * else is skipped with the reason. Creation starts after commit.
     */
    public Settlement settleAtApproval(Request request) {
        int queued = 0;
        int pending = 0;
        int skipped = 0;
        for (RequestRecipient recipient : recipientRepository.findWithLockByRequestId(request.getId())) {
            if (recipient.getStatus() != RequestRecipientStatus.QUEUED
                    && recipient.getStatus() != RequestRecipientStatus.PENDING_JOIN) {
                skipped++;
                continue;
            }
            Long userId = recipient.getUserId();
            if (userId == null) {
                WorkspaceInvitation invitation = invitationRepository
                        .findById(recipient.getInvitationId()).orElse(null);
                if (invitation != null && invitation.getStatus() == WorkspaceInvitationStatus.PENDING) {
                    recipient.mark(RequestRecipientStatus.PENDING_JOIN, null);
                    pending++;
                    continue;
                }
                // Accepted without the claim reaching this row cannot happen
                // through the claim path, but an accepted invitation still
                // names the person, so use it rather than lose them.
                Long accepted = invitation != null && invitation.getStatus() == WorkspaceInvitationStatus.ACCEPTED
                        ? invitation.getAcceptedUserId() : null;
                if (accepted == null
                        || recipientRepository.findByRequestIdOrderByIdAsc(request.getId()).stream()
                                .anyMatch(other -> accepted.equals(other.getUserId()))) {
                    recipient.mark(RequestRecipientStatus.SKIPPED_INELIGIBLE,
                            accepted == null ? REASON_INVITATION_CLOSED : REASON_DUPLICATE);
                    skipped++;
                    continue;
                }
                recipient.joined(accepted);
                userId = accepted;
            }
            User user = userRepository.findById(userId).orElse(null);
            if (user != null && activeMember(request.getWorkspaceId(), user)) {
                recipient.mark(RequestRecipientStatus.QUEUED, null);
                queued++;
            } else {
                recipient.mark(RequestRecipientStatus.SKIPPED_INELIGIBLE, REASON_NOT_MEMBER);
                skipped++;
            }
        }
        if (queued > 0) {
            materializer.triggerAfterCommit();
        }
        return new Settlement(queued, pending, skipped);
    }

    /** A request that ends undecided (canceled or rejected) creates nothing for anyone. */
    public void closeUndecided(Request request) {
        for (RequestRecipient recipient : recipientRepository.findWithLockByRequestId(request.getId())) {
            if (recipient.getStatus() == RequestRecipientStatus.QUEUED
                    || recipient.getStatus() == RequestRecipientStatus.PENDING_JOIN) {
                recipient.mark(RequestRecipientStatus.CANCELED, null);
            }
        }
    }

    // ── joining ────────────────────────────────────────────────────────────

    /**
     * An invitee became a member. Their waiting rows name them from now on,
     * and each is queued unless its request's granted period has already
     * ended, in which case nothing is made. A row of a request not yet decided
     * is queued too; its approval evaluates it like any other member.
     *
     * <p>Runs inside the claim, which runs inside account activation or a
     * profile save, so it only writes rows and never fails for a reason the
     * person joining could not fix.</p>
     */
    public void onInvitationClaimed(WorkspaceInvitation invitation, long userId) {
        boolean queuedApproved = false;
        LocalDate today = ClockConfig.todayKst(clock);
        for (RequestRecipient recipient : recipientRepository.findWithLockByInvitationIdAndStatus(
                invitation.getId(), RequestRecipientStatus.PENDING_JOIN)) {
            boolean alreadyNamed = recipientRepository.findByRequestIdOrderByIdAsc(recipient.getRequestId())
                    .stream().anyMatch(other -> other != recipient && Objects.equals(userId, other.getUserId()));
            if (alreadyNamed) {
                recipient.mark(RequestRecipientStatus.SKIPPED_INELIGIBLE, REASON_DUPLICATE);
                continue;
            }
            recipient.joined(userId);
            Request request = requestRepository.findById(recipient.getRequestId()).orElse(null);
            if (request == null || request.getStatus() != RequestStatus.APPROVED) {
                recipient.mark(RequestRecipientStatus.QUEUED, null);
                continue;
            }
            LocalDate end = reviewRepository.findByRequestId(request.getId())
                    .map(RequestReview::getGrantedEndDate).orElse(null);
            if (end != null && end.isBefore(today)) {
                recipient.mark(RequestRecipientStatus.SKIPPED_EXPIRED, REASON_EXPIRED);
            } else {
                recipient.mark(RequestRecipientStatus.QUEUED, null);
                queuedApproved = true;
            }
        }
        if (queuedApproved) {
            materializer.triggerAfterCommit();
        }
    }

    // ── retry ──────────────────────────────────────────────────────────────

    /** Queues a failed recipient again. Only a failure can be retried. */
    public RequestRecipient retry(Request request, UUID recipientId) {
        RequestRecipient recipient = recipientRepository.findByPublicIdAndRequestId(recipientId, request.getId())
                .flatMap(found -> recipientRepository.findWithLockById(found.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                        "리소스를 찾을 수 없습니다", "해당 대상자가 존재하지 않습니다."));
        if (recipient.getStatus() != RequestRecipientStatus.FAILED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.REQUEST_RECIPIENT_NOT_RETRYABLE,
                    "다시 시도할 수 없는 대상자입니다", "생성에 실패한 대상자만 다시 시도할 수 있습니다.");
        }
        recipient.mark(RequestRecipientStatus.QUEUED, null);
        materializer.triggerAfterCommit();
        return recipient;
    }

    // ── read ───────────────────────────────────────────────────────────────

    /**
     * The recipients of each request, batched. {@code showInvitee} decides per
     * request whether the address or 학번 an invitation was sent to may be
     * shown; the account name of a member is shown to anyone who can read the
     * request, as the member list already does.
     */
    public Map<Long, List<RequestRecipientResponse>> responses(List<Request> requests,
            Predicate<Request> showInvitee) {
        if (requests.isEmpty()) {
            return Map.of();
        }
        Map<Long, Request> byId = requests.stream()
                .collect(Collectors.toMap(Request::getId, request -> request, (a, b) -> a));
        List<RequestRecipient> rows = recipientRepository.findByRequestIdInOrderByIdAsc(byId.keySet());
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, User> users = userRepository.findAllById(rows.stream().map(RequestRecipient::getUserId)
                        .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        Map<Long, WorkspaceInvitation> invitations = invitationRepository.findAllById(rows.stream()
                        .map(RequestRecipient::getInvitationId).filter(Objects::nonNull)
                        .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(WorkspaceInvitation::getId, invitation -> invitation));
        Map<ResourceType, Map<Long, UUID>> resources = new HashMap<>();
        for (ResourceType type : List.of(ResourceType.VM, ResourceType.LLM_API_KEY)) {
            List<Long> ids = rows.stream()
                    .filter(row -> row.getResourceId() != null
                            && byId.get(row.getRequestId()).getResourceType() == type)
                    .map(RequestRecipient::getResourceId).toList();
            resources.put(type, publicIds(type == ResourceType.VM ? "vms" : "llm_api_keys", ids));
        }
        Map<Long, Boolean> inviteeVisible = new HashMap<>();
        Map<Long, List<RequestRecipientResponse>> out = new LinkedHashMap<>();
        for (RequestRecipient row : rows) {
            Request request = byId.get(row.getRequestId());
            boolean visible = inviteeVisible.computeIfAbsent(request.getId(), id -> showInvitee.test(request));
            User user = row.getUserId() == null ? null : users.get(row.getUserId());
            WorkspaceInvitation invitation = row.getInvitationId() == null ? null
                    : invitations.get(row.getInvitationId());
            String invitee = !visible || invitation == null ? null
                    : invitation.getInviteeEmail() != null ? invitation.getInviteeEmail()
                    : invitation.getInviteeStudentNo();
            Map<Long, UUID> typeResources = resources.getOrDefault(request.getResourceType(), Map.of());
            out.computeIfAbsent(request.getId(), id -> new ArrayList<>()).add(new RequestRecipientResponse(
                    row.getPublicId(),
                    user != null ? user.getPublicId() : null,
                    user != null ? user.getName() : null,
                    invitee,
                    row.getStatus(),
                    row.getResourceId() == null ? null : typeResources.get(row.getResourceId()),
                    row.getReason()));
        }
        return out;
    }

    private Map<Long, UUID> publicIds(String table, List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, UUID> out = new HashMap<>();
        // The table name is one of two literals above, never input.
        jdbcTemplate.query("select id, public_id from " + table + " where id in ("
                        + ids.stream().map(id -> "?").collect(Collectors.joining(",")) + ")",
                rs -> {
                    out.put(rs.getLong("id"), rs.getObject("public_id", UUID.class));
                }, ids.toArray());
        return out;
    }

    private boolean activeMember(Long workspaceId, User user) {
        return user.getStatus() == UserStatus.ACTIVE
                && workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, user.getId()).isPresent();
    }
}
