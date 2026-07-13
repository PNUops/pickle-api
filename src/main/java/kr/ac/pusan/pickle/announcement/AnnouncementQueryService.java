package kr.ac.pusan.pickle.announcement;

import kr.ac.pusan.pickle.announcement.dto.AnnouncementView;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Contract {@code listAnnouncements}: sender-org visibility, newest first. */
@Service
public class AnnouncementQueryService {

    private final AnnouncementRepository announcementRepository;

    public AnnouncementQueryService(AnnouncementRepository announcementRepository) {
        this.announcementRepository = announcementRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AnnouncementView> list(AuthenticatedUser actor, int page, int size) {
        Page<Announcement> result;
        if (actor.role() == UserRole.SYS_ADMIN) {
            result = announcementRepository.findAllByOrderByIdDesc(PageRequest.of(page, size));
        } else {
            if (actor.orgId() == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                        "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
            }
            result = announcementRepository.findVisibleToOrgAdmin(AnnouncementScope.ALL,
                    actor.orgId(), PageRequest.of(page, size));
        }
        return PageResponse.of(result.getContent().stream().map(AnnouncementView::from).toList(),
                result);
    }
}
