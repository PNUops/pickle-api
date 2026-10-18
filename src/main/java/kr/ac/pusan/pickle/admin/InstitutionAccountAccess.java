package kr.ac.pusan.pickle.admin;

import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionAuthorization;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.stereotype.Component;

/** Account metadata, finance and credential admission remain institution-bound. */
@Component
public class InstitutionAccountAccess {
    private final InstitutionAuthorization authorization;
    public InstitutionAccountAccess(InstitutionAuthorization authorization) { this.authorization = authorization; }
    public boolean work(AuthenticatedUser actor, InstitutionWork work, long institutionId) {
        if (!java.util.List.of(InstitutionWork.INSTITUTION_ACCOUNT_READ, InstitutionWork.INSTITUTION_ACCOUNT_FINANCE_READ,
                InstitutionWork.INSTITUTION_ACCOUNT_MANAGE, InstitutionWork.INSTITUTION_ACCOUNT_CREDENTIAL_MANAGE).contains(work)) { return false; }
        if (actor.role() == UserRole.SYS_ADMIN) { return true; }
        if (!work.write() && InstitutionLlmAccess.systemRead(actor)) { return true; }
        return authorization.institutionTarget(institutionId)
                .map(target -> authorization.decision(actor, work, target).allowed()).orElse(false);
    }
    public boolean selectable(AuthenticatedUser actor, long institutionId) {
        return work(actor, InstitutionWork.INSTITUTION_ACCOUNT_READ, institutionId)
                || work(actor, InstitutionWork.INSTITUTION_ACCOUNT_FINANCE_READ, institutionId)
                || work(actor, InstitutionWork.INSTITUTION_ACCOUNT_MANAGE, institutionId)
                || work(actor, InstitutionWork.INSTITUTION_ACCOUNT_CREDENTIAL_MANAGE, institutionId);
    }
}
