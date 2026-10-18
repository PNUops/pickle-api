package kr.ac.pusan.pickle.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.LlmKeyBrief;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.LlmKeyContext;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyRepository;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.springframework.stereotype.Component;

/** Existing live key panels for an LLM key approval decision. */
@Component
public class LlmKeyApprovalContextContributor implements ApprovalContextContributor {

    private static final List<LlmApiKeyStatus> NON_TERMINAL = List.of(
            LlmApiKeyStatus.PENDING, LlmApiKeyStatus.ACTIVE, LlmApiKeyStatus.SUSPENDED);

    private final LlmApiKeyRepository keyRepository;
    private final WorkspaceRepository workspaceRepository;
    private final OrgRepository orgRepository;
    private final InstitutionLlmAccess access;
    private final kr.ac.pusan.pickle.request.InstitutionRequestAccess requests;
    private final InstitutionLlmAccountReferences accountReferences;

    public LlmKeyApprovalContextContributor(LlmApiKeyRepository keyRepository,
            WorkspaceRepository workspaceRepository, OrgRepository orgRepository, InstitutionLlmAccess access, kr.ac.pusan.pickle.request.InstitutionRequestAccess requests, InstitutionLlmAccountReferences accountReferences) {
        this.accountReferences = accountReferences;
        this.access = access; this.requests = requests;
        this.keyRepository = keyRepository;
        this.workspaceRepository = workspaceRepository;
        this.orgRepository = orgRepository;
    }

    @Override
    public ResourceType type() {
        return ResourceType.LLM_API_KEY;
    }

    @Override
    public Contribution contribute(Request request, List<Long> applicantWorkspaceIds, AuthenticatedUser actor) {
        Instant now = Instant.now();
        List<LlmApiKey> applicant = applicantWorkspaceIds.isEmpty()
                ? List.of()
                : keyRepository.findCurrentByWorkspaceIdIn(applicantWorkspaceIds, NON_TERMINAL, now);
        List<LlmApiKey> workspace = keyRepository.findCurrentByWorkspaceIdIn(
                List.of(request.getWorkspaceId()), NON_TERMINAL, now);
        Map<Long, Workspace> workspaces = workspaceRepository.findAllById(
                        java.util.stream.Stream.concat(applicant.stream(), workspace.stream())
                                .map(LlmApiKey::getWorkspaceId).distinct().toList())
                .stream().collect(Collectors.toMap(Workspace::getId, Function.identity()));
        Map<Long, Org> orgs = orgRepository.findAllById(java.util.stream.Stream.concat(applicant.stream(), workspace.stream())
                .map(LlmApiKey::getOrgId).distinct().toList()).stream().collect(Collectors.toMap(Org::getId, Function.identity()));
        List<LlmKeyBrief> applicantKeys = applicant.stream()
                .filter(key -> visible(actor, request, key))
                .map(key -> brief(key, workspaces.get(key.getWorkspaceId()), orgs.get(key.getOrgId()), actor, now)).toList();
        List<LlmKeyBrief> workspaceKeys = workspace.stream()
                .filter(key -> visible(actor, request, key))
                .map(key -> brief(key, workspaces.get(key.getWorkspaceId()), orgs.get(key.getOrgId()), actor, now)).toList();
        return Contribution.llmKey(new LlmKeyContext(applicantKeys, workspaceKeys, accountReferences.forRequest(actor, request)));
    }

    private LlmKeyBrief brief(LlmApiKey key, Workspace workspace, Org org, AuthenticatedUser actor, Instant now) {
        return new LlmKeyBrief(key.getPublicId(), key.getName(),
                workspace == null ? null : workspace.getPublicId(),
                workspace == null ? "" : workspace.getName(), key.effectiveStatus(now),
                key.getExpiresAt(), access.metadata(actor, key) || access.observation(actor, key) ? key.getRpm() : null,
                access.metadata(actor, key) || access.observation(actor, key) ? key.getTpm() : null,
                access.metadata(actor, key) || access.observation(actor, key) ? key.getConcurrency() : null,
                access.metadata(actor, key) || access.observation(actor, key) ? key.getDailyTokens() : null, access.cost(actor, key) ? key.getCreditLimit() : null, access.cost(actor, key) ? key.getCreditLimitReset() : null,
                access.cost(actor, key) ? key.isCreditAxisConnected() : null, org.getPublicId(), access.selectable(actor, key), access.cost(actor, key));
    }
    private boolean visible(AuthenticatedUser actor, Request request, LlmApiKey key) {
        return access.metadata(actor, key) || access.observation(actor, key) || access.cost(actor, key)
                || (request.getResourceType() == ResourceType.LLM_API_KEY && request.getOrgId().equals(key.getOrgId())
                    && request.getWorkspaceId().equals(key.getWorkspaceId())
                    && requests.decide(actor, request.getResourceType(), request.getOrgId(), request.getWorkspaceId()));
    }
}
