package kr.ac.pusan.pickle.admin;

import kr.ac.pusan.pickle.admin.dto.AdminPerformanceOverviewResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.observation.PlatformHardwareObservationService;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Role-specific projections keep optional metadata outside the business transaction. */
@Service
public class AdminPerformanceOverviewService {
    private final PlatformHardwareObservationService hardware;
    private final AdminPerformanceBusinessReader business;
    public AdminPerformanceOverviewService(PlatformHardwareObservationService hardware,
            AdminPerformanceBusinessReader business) { this.hardware = hardware; this.business = business; }
    public AdminPerformanceOverviewResponse get(AuthenticatedUser actor) {
        if (actor.role() != UserRole.SYS_ADMIN && actor.role() != UserRole.SYS_MANAGER) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없어요", "시스템 관리 업무 권한이 필요해요.");
        }
        return new AdminPerformanceOverviewResponse(hardware.current(),
                actor.role() == UserRole.SYS_ADMIN ? business.read() : null);
    }
}
