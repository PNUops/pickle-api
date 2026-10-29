package kr.ac.pusan.pickle.announcement;

import kr.ac.pusan.pickle.announcement.dto.AnnouncementView;
import kr.ac.pusan.pickle.announcement.dto.AnnouncementDetailResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionAuthorization;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import java.util.UUID;

/** Contract {@code listAnnouncements}: sender-org visibility, newest first. */
@Service
public class AnnouncementQueryService {

    private final AnnouncementRepository announcementRepository;
    private final kr.ac.pusan.pickle.orgs.OrgRepository orgRepository;
    private final kr.ac.pusan.pickle.workspace.WorkspaceRepository workspaceRepository;
    private final InstitutionAuthorization authorization;

    public AnnouncementQueryService(AnnouncementRepository announcementRepository,
            kr.ac.pusan.pickle.orgs.OrgRepository orgRepository,
            kr.ac.pusan.pickle.workspace.WorkspaceRepository workspaceRepository, InstitutionAuthorization authorization) {
        this.announcementRepository = announcementRepository;
        this.orgRepository = orgRepository;
        this.workspaceRepository = workspaceRepository;
        this.authorization = authorization;
    }

    @Transactional(readOnly = true)
    public PageResponse<AnnouncementView> list(AuthenticatedUser actor, int page, int size) {
        // Visibility follows the sender's organisation. The org tier sees what
        // the administrators of the organisations it holds a role in have sent,
        // which since V90 can be several, plus every platform-wide broadcast.
        PageRequest pageable = PageRequest.of(page, size);
        Page<Announcement> result = !actor.role().isSysTier()
                ? announcementRepository.findVisibleToOrgAdmin(
                        AnnouncementScope.ALL, readableInstitutions(actor), pageable)
                : announcementRepository.findAllByOrderByIdDesc(pageable);
        return PageResponse.of(result.getContent().stream().map(this::toView).toList(), result);
    }

    @Transactional(readOnly = true)
    public AnnouncementDetailResponse get(AuthenticatedUser actor, UUID id) {
        Announcement announcement = (!actor.role().isSysTier()
                ? announcementRepository.findVisibleByPublicId(id, AnnouncementScope.ALL, readableInstitutions(actor))
                : announcementRepository.findByPublicId(id)).orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                        "발송건을 찾을 수 없습니다", "조회할 수 있는 발송건이 없습니다."));
        var view = toView(announcement);
        return AnnouncementDetailResponse.from(announcement, view.orgId(), view.workspaceId());
    }

    /**
     * An announcement names its scope target by public id, so the org or
     * workspace row behind it is read here rather than in the view.
     */
    private java.util.List<UUID> readableInstitutions(AuthenticatedUser actor) {
        var institutions = authorization.sources(actor, InstitutionWork.INSTITUTION_ANNOUNCEMENT_READ).stream()
                .map(source -> source.source().institutionId()).distinct().toList();
        if (institutions.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "발송 이력을 조회할 권한이 없습니다.");
        }
        return institutions;
    }

    private AnnouncementView toView(Announcement announcement) {
        return AnnouncementView.from(announcement,
                announcement.getOrgId() == null ? null
                        : orgRepository.findById(announcement.getOrgId())
                                .map(kr.ac.pusan.pickle.orgs.Org::getPublicId).orElse(null),
                announcement.getWorkspaceId() == null ? null
                        : workspaceRepository.findById(announcement.getWorkspaceId())
                                .map(kr.ac.pusan.pickle.workspace.Workspace::getPublicId).orElse(null));
    }
}
