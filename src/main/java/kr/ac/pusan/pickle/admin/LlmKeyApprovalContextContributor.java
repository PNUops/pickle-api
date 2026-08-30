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

    public LlmKeyApprovalContextContributor(LlmApiKeyRepository keyRepository,
            WorkspaceRepository workspaceRepository) {
        this.keyRepository = keyRepository;
        this.workspaceRepository = workspaceRepository;
    }

    @Override
    public ResourceType type() {
        return ResourceType.LLM_API_KEY;
    }

    @Override
    public Contribution contribute(Request request, List<Long> applicantWorkspaceIds) {
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
        List<LlmKeyBrief> applicantKeys = applicant.stream()
                .map(key -> brief(key, workspaces.get(key.getWorkspaceId()), now)).toList();
        List<LlmKeyBrief> workspaceKeys = workspace.stream()
                .map(key -> brief(key, workspaces.get(key.getWorkspaceId()), now)).toList();
        return Contribution.llmKey(new LlmKeyContext(applicantKeys, workspaceKeys));
    }

    private static LlmKeyBrief brief(LlmApiKey key, Workspace workspace, Instant now) {
        return new LlmKeyBrief(key.getPublicId(), key.getName(),
                workspace == null ? null : workspace.getPublicId(),
                workspace == null ? "" : workspace.getName(), key.effectiveStatus(now),
                key.getExpiresAt(), key.getRpm(), key.getTpm(), key.getConcurrency(),
                key.getDailyTokens(), key.getCreditLimit(), key.getCreditLimitReset(),
                key.isCreditAxisConnected());
    }
}
