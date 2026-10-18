package kr.ac.pusan.pickle.admin;

import java.util.List;
import kr.ac.pusan.pickle.admin.dto.InstitutionLlmAccountReference;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountRepository;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountSelectionService;
import kr.ac.pusan.pickle.request.InstitutionRequestAccess;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Read-only command references; never account inventory, finance or credential admission. */
@Component
public class InstitutionLlmAccountReferences {
    private final OpenRouterAccountRepository accounts;
    private final OpenRouterAccountSelectionService selection;
    private final InstitutionLlmAccess keys;
    private final InstitutionRequestAccess requests;
    private final kr.ac.pusan.pickle.security.institution.InstitutionAuthorization authorization;
    private final kr.ac.pusan.pickle.orgs.OrgRepository institutions;
    private final kr.ac.pusan.pickle.workspace.WorkspaceRepository workspaces;
    private final tools.jackson.databind.ObjectMapper json;
    public InstitutionLlmAccountReferences(OpenRouterAccountRepository accounts, OpenRouterAccountSelectionService selection,
            InstitutionLlmAccess keys, InstitutionRequestAccess requests,
            kr.ac.pusan.pickle.security.institution.InstitutionAuthorization authorization,
            kr.ac.pusan.pickle.orgs.OrgRepository institutions, kr.ac.pusan.pickle.workspace.WorkspaceRepository workspaces,
            tools.jackson.databind.ObjectMapper json) {
        this.authorization = authorization; this.institutions = institutions; this.workspaces = workspaces; this.json = json;
        this.accounts = accounts; this.selection = selection; this.keys = keys; this.requests = requests;
    }
    public @Nullable List<InstitutionLlmAccountReference> forKey(AuthenticatedUser actor, LlmApiKey key) {
        return keys.mutation(actor, InstitutionWork.LLM_KEY_BUDGET, key) ? ofInstitution(key.getOrgId()) : null;
    }
    public @Nullable List<InstitutionLlmAccountReference> forRequest(AuthenticatedUser actor, Request request) {
        return requests.paidDecision(actor, request, false) ? ofInstitution(request.getOrgId()) : null;
    }
    @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<InstitutionLlmAccountReference> forRoster(AuthenticatedUser original, java.util.UUID institutionId, java.util.UUID workspaceId) {
        AuthenticatedUser actor = keys.currentForRead(original);
        var institution = institutions.findByPublicId(institutionId).orElseThrow(InstitutionLlmAccountReferences::notFound);
        var workspace = workspaces.findByPublicIdAndDeletedAtIsNull(workspaceId).orElseThrow(InstitutionLlmAccountReferences::notFound);
        boolean permitted = actor.role() == kr.ac.pusan.pickle.user.UserRole.SYS_ADMIN
                || authorization.resourceTarget(kr.ac.pusan.pickle.security.institution.InstitutionWorkGroup.LLM, institution.getId(), workspace.getId())
                    .map(target -> authorization.sameSourceDecision(actor, InstitutionWork.LLM_ROSTER_ISSUE, InstitutionWork.LLM_KEY_BUDGET, target).allowed()).orElse(false);
        if (!permitted) { throw notFound(); }
        return ofInstitution(institution.getId());
    }
    private static kr.ac.pusan.pickle.common.error.ApiException notFound() {
        return new kr.ac.pusan.pickle.common.error.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                kr.ac.pusan.pickle.common.error.ErrorCodes.RESOURCE_NOT_FOUND, "리소스를 찾을 수 없습니다", "이 명단 발급 대상의 사업 계정 참조를 찾을 수 없습니다.");
    }
    private List<InstitutionLlmAccountReference> ofInstitution(long institution) {
        return accounts.findAll().stream().filter(account -> account.getOrgId() == institution)
                .sorted(java.util.Comparator.comparing(kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccount::getName))
                .map(account -> {
                    boolean eligible = selection.eligible(account);
                    return new InstitutionLlmAccountReference(account.getPublicId(), account.getName(), eligible,
                            eligible ? kr.ac.pusan.pickle.llm.CreditModelPatterns.fromJson(json, account.getDefaultCreditAllowedModels(), "account " + account.getPublicId()) : null,
                            eligible ? kr.ac.pusan.pickle.llm.CreditModelPatterns.fromJson(json, account.getDefaultCreditDeniedModels(), "account " + account.getPublicId()) : null);
                }).toList();
    }
}
