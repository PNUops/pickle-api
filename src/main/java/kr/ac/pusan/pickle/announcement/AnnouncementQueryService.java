package kr.ac.pusan.pickle.announcement;

import kr.ac.pusan.pickle.announcement.dto.AnnouncementView;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Contract {@code listAnnouncements}: sender-org visibility, newest first. */
@Service
public class AnnouncementQueryService {

    private final AnnouncementRepository announcementRepository;
    private final kr.ac.pusan.pickle.orgs.OrgRepository orgRepository;
    private final kr.ac.pusan.pickle.workspace.WorkspaceRepository workspaceRepository;

    public AnnouncementQueryService(AnnouncementRepository announcementRepository,
            kr.ac.pusan.pickle.orgs.OrgRepository orgRepository,
            kr.ac.pusan.pickle.workspace.WorkspaceRepository workspaceRepository) {
        this.announcementRepository = announcementRepository;
        this.orgRepository = orgRepository;
        this.workspaceRepository = workspaceRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AnnouncementView> list(AuthenticatedUser actor, int page, int size) {
        // Visibility follows the sender's organisation. The org tier sees what
        // the administrators of the organisations it holds a role in have sent,
        // which since V90 can be several, plus every platform-wide broadcast.
        PageRequest pageable = PageRequest.of(page, size);
        Page<Announcement> result = actor.role().isOrgTier()
                ? announcementRepository.findVisibleToOrgAdmin(
                        AnnouncementScope.ALL, actor.readableOrgIds(), pageable)
                : announcementRepository.findAllByOrderByIdDesc(pageable);
        return PageResponse.of(result.getContent().stream().map(this::toView).toList(), result);
    }

    /**
     * An announcement names its scope target by public id, so the org or
     * workspace row behind it is read here rather than in the view.
     */
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
