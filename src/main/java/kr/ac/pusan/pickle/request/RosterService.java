package kr.ac.pusan.pickle.request;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.profile.ProfileValidator;
import kr.ac.pusan.pickle.request.dto.ResolveRosterRequest;
import kr.ac.pusan.pickle.request.dto.ResolveRosterResponse;
import kr.ac.pusan.pickle.request.dto.ResolveRosterResponse.RosterEntryResult;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.workspace.InvitationWriter;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads where each 학번 of a pasted class roster stands in a workspace, so a
 * request can name its recipients by 학번 knowing what submitting will do.
 * Writes nothing: members are added and invitations opened only when the
 * request is submitted.
 *
 * <p>Answers whether an ACTIVE account holds each 학번 and its name, which is
 * more than an invitation answers. It is open to exactly the people who may
 * name recipients here, and capped per minute like invitation; there is no
 * hourly budget, because nothing is spent.</p>
 */
@Service
public class RosterService {

    static final int RESOLVE_CALLS_PER_MINUTE = 10;
    static final String RESOLVE_CALL_SCOPE = "workspace_roster_resolve";

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final OrgRepository orgRepository;
    private final RequestRecipientService recipientService;
    private final RateLimitService rateLimitService;

    public RosterService(WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository, OrgRepository orgRepository,
            RequestRecipientService recipientService, RateLimitService rateLimitService) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.orgRepository = orgRepository;
        this.recipientService = recipientService;
        this.rateLimitService = rateLimitService;
    }

    @Transactional(readOnly = true)
    public ResolveRosterResponse resolve(AuthenticatedUser actor, UUID publicWorkspaceId,
            ResolveRosterRequest request) {
        rateLimitService.hit(RESOLVE_CALL_SCOPE, "user:" + actor.id(), RESOLVE_CALLS_PER_MINUTE);
        Workspace workspace = workspaceRepository.findByPublicIdAndDeletedAtIsNull(publicWorkspaceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                        "리소스를 찾을 수 없습니다", "해당 워크스페이스가 존재하지 않습니다."));
        WorkspaceMember membership = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(workspace.getId(), actor.id()).orElse(null);
        Long orgId = request.orgId() == null ? null
                : orgRepository.findByPublicId(request.orgId()).map(Org::getId).orElse(null);
        if (!RecipientNamers.mayName(actor, membership, orgId)) {
            throw forbidden("이 워크스페이스의 신청 대상자는 워크스페이스 소유자나 이 기관의 신청을 승인할 수 있는 관리자만 "
                    + "확인할 수 있습니다.");
        }
        // Asked after the role so the kind of a workspace the caller may not
        // act on is not answered either.
        if (workspace.getKind() == WorkspaceKind.PERSONAL) {
            throw forbidden("개인 워크스페이스에는 학번으로 대상자를 지정할 수 없습니다.");
        }

        List<RosterEntryResult> results = new ArrayList<>(request.studentNos().size());
        Set<String> seen = new HashSet<>();
        for (String given : request.studentNos()) {
            String studentNo = given == null ? "" : given.strip();
            if (!ProfileValidator.isStudentNoFormat(studentNo)) {
                results.add(new RosterEntryResult(studentNo, RosterEntryStatus.INVALID, null, null, null));
                continue;
            }
            if (!seen.add(InvitationWriter.studentNoKey(studentNo))) {
                results.add(new RosterEntryResult(studentNo, RosterEntryStatus.DUPLICATE, null, null, null));
                continue;
            }
            RequestRecipientService.RosterEntry entry = recipientService.rosterEntry(workspace, studentNo);
            results.add(new RosterEntryResult(studentNo, entry.status(),
                    entry.user() != null ? entry.user().getPublicId() : null,
                    entry.invitation() != null ? entry.invitation().getPublicId() : null,
                    entry.user() != null ? entry.user().getName() : null));
        }
        return new ResolveRosterResponse(results);
    }

    private static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.REQUEST_RECIPIENTS_FORBIDDEN,
                "대상자를 지정할 권한이 없습니다", detail);
    }
}
