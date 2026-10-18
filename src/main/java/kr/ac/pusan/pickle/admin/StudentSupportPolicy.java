package kr.ac.pusan.pickle.admin;

import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionAuthorization;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkTarget;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Global support visibility is separate from institution supply and account administration. */
@Component
public final class StudentSupportPolicy {
    private final InstitutionAuthorization authorization;

    public StudentSupportPolicy(InstitutionAuthorization authorization) {
        this.authorization = authorization;
    }

    /** A command's necessary account reference does not expose the support relationship graph. */
    public boolean canLookupStudent(AuthenticatedUser actor) {
        return actor.role().isSysTier() || global(actor, InstitutionWork.STUDENT_LOOKUP)
                || global(actor, InstitutionWork.STUDENT_SUPPORT_READ)
                || global(actor, InstitutionWork.STUDENT_PROFILE_CORRECT);
    }

    public boolean canReadStudentSupport(AuthenticatedUser actor) {
        return actor.role().isSysTier() || global(actor, InstitutionWork.STUDENT_SUPPORT_READ);
    }

    public boolean canLookupStudent(AuthenticatedUser actor, UserRole target) {
        return canLookupStudent(actor) && (actor.role().isSysTier() || !target.isSysTier());
    }

    public boolean canReadStudentProfile(AuthenticatedUser actor, UserRole target) {
        return canLookupStudent(actor, target)
                && (canReadStudentSupport(actor) || canCorrectStudentProfile(actor, target));
    }

    public boolean canCorrectStudentProfile(AuthenticatedUser actor, UserRole target) {
        return actor.role() == UserRole.SYS_ADMIN
                || !actor.role().isSysTier() && !target.isSysTier()
                    && global(actor, InstitutionWork.STUDENT_PROFILE_CORRECT);
    }

    public void requireStudentLookup(AuthenticatedUser actor) {
        if (!canLookupStudent(actor)) { throw forbidden(); }
    }

    public void requireStudentSupport(AuthenticatedUser actor) {
        if (!canReadStudentSupport(actor)) { throw forbidden(); }
    }

    public void requireStudentProfileCorrection(AuthenticatedUser actor, UserRole target) {
        if (!canCorrectStudentProfile(actor, target)) { throw forbidden(); }
    }

    private boolean global(AuthenticatedUser actor, InstitutionWork work) {
        return authorization.decision(actor, work, InstitutionWorkTarget.global()).allowed();
    }

    public static boolean canEnableAccount(AuthenticatedUser actor) { return actor.role() == UserRole.SYS_ADMIN; }

    public static void requireEnableImpact(AuthenticatedUser actor) {
        if (!canEnableAccount(actor)) { throw forbidden(); }
    }

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                "접근 권한이 없습니다", "이 지원 작업을 수행할 권한이 없습니다.");
    }

}
