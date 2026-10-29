package kr.ac.pusan.pickle.workspace;

import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionActorProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Explicit fresh-read and post-administration-lock actor entries. */
@Component
public class LifecycleActors {
    private final InstitutionActorProvider actors;
    private final jakarta.persistence.EntityManager entityManager;
    private final kr.ac.pusan.pickle.config.MfaProperties mfaProperties;
    private final kr.ac.pusan.pickle.mfa.MfaService mfaService;
    public LifecycleActors(InstitutionActorProvider actors, kr.ac.pusan.pickle.config.MfaProperties mfaProperties,
            kr.ac.pusan.pickle.mfa.MfaService mfaService, jakarta.persistence.EntityManager entityManager) {
        this.entityManager = entityManager;
        this.actors = actors; this.mfaProperties = mfaProperties; this.mfaService = mfaService;
    }
    public AuthenticatedUser current(AuthenticatedUser actor) {
        return enforceMfa(actors.reloadCurrent(actor));
    }
    public AuthenticatedUser currentAfterAdministrationLock(AuthenticatedUser actor) {
        actors.verifyAdministrationTransaction();
        entityManager.flush();
        return enforceMfa(actors.reloadAfterAdministrationLock(actor));
    }
    private AuthenticatedUser enforceMfa(AuthenticatedUser current) {
        if (mfaProperties.enforceAdmin() && current.role().isSysTier() && !mfaService.isEnrolled(current.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.MFA_ENROLLMENT_REQUIRED,
                    "2단계 인증 등록이 필요합니다", "관리자 계정은 2단계 인증 등록 후 이용할 수 있습니다.");
        }
        return current;
    }
}
