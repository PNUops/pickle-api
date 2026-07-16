package kr.ac.pusan.pickle.announcement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.announcement.dto.AnnouncementCreateRequest;
import kr.ac.pusan.pickle.announcement.dto.AnnouncementView;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.group.GroupRepository;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.orgs.OrgMembershipSql;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Announcement send (contract {@code createAnnouncement}). Scope rules:
 * ALL is SYS_ADMIN-only (403); an ORG_ADMIN's ORG scope is pinned to their own
 * org (mismatch 422); GROUP scope is gated — for an ORG_ADMIN — on the group
 * having resources (vm_requests / non-DELETED VMs) in their org (else 404,
 * existence masked); a gated group's recipients are all its ACTIVE members.
 * ORG-scope recipients follow the canonical <b>derived org membership</b>
 * ({@link OrgMembershipSql}) — ACTIVE members of org-linked groups plus the
 * org's ORG_ADMINs.
 *
 * <p>Fan-out is a synchronous INSERT…SELECT into {@code notifications} inside
 * this transaction — the in-app rows exist when the 201 returns; email leaves
 * asynchronously via the dispatcher. A per-author sliding budget (10/hour,
 * {@code auth_rate_limits} scope {@code announce}) answers 429 + Retry-After.</p>
 */
@Service
public class AnnouncementService {

    static final int MAX_PER_HOUR = 10;
    static final String RATE_SCOPE = "announce";

    private final AnnouncementRepository announcementRepository;
    private final GroupRepository groupRepository;
    private final OrgRepository orgRepository;
    private final JdbcTemplate jdbcTemplate;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;

    public AnnouncementService(AnnouncementRepository announcementRepository,
            GroupRepository groupRepository, OrgRepository orgRepository,
            JdbcTemplate jdbcTemplate, RateLimitService rateLimitService,
            AuditService auditService) {
        this.announcementRepository = announcementRepository;
        this.groupRepository = groupRepository;
        this.orgRepository = orgRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.rateLimitService = rateLimitService;
        this.auditService = auditService;
    }

    @Transactional
    public AnnouncementView create(AuthenticatedUser actor, AnnouncementCreateRequest request,
            String ip) {
        AnnouncementScope scope = request.scope();
        List<FieldValidationError> errors = new ArrayList<>();
        Long orgId = null;
        Long groupId = null;
        switch (scope) {
            case ALL -> {
                if (actor.role() != UserRole.SYS_ADMIN) {
                    throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                            "접근 권한이 없습니다", "전체 공지는 시스템 관리자만 발송할 수 있습니다.");
                }
                if (request.orgId() != null) {
                    errors.add(new FieldValidationError("orgId", "전체 공지에는 기관을 지정할 수 없습니다."));
                }
                if (request.groupId() != null) {
                    errors.add(new FieldValidationError("groupId", "전체 공지에는 그룹을 지정할 수 없습니다."));
                }
            }
            case ORG -> {
                if (request.groupId() != null) {
                    errors.add(new FieldValidationError("groupId", "기관 공지에는 그룹을 지정할 수 없습니다."));
                }
                if (actor.role() == UserRole.ORG_ADMIN) {
                    if (actor.orgId() == null) {
                        throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                                "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
                    }
                    if (request.orgId() != null && !request.orgId().equals(actor.orgId())) {
                        errors.add(new FieldValidationError("orgId",
                                "자기 기관에만 기관 공지를 발송할 수 있습니다."));
                    }
                    orgId = actor.orgId();
                } else {
                    if (request.orgId() == null) {
                        errors.add(new FieldValidationError("orgId", "기관 공지에는 대상 기관이 필요합니다."));
                    }
                    orgId = request.orgId();
                }
            }
            case GROUP -> {
                if (request.orgId() != null) {
                    errors.add(new FieldValidationError("orgId", "그룹 공지에는 기관을 지정할 수 없습니다."));
                }
                if (request.groupId() == null) {
                    errors.add(new FieldValidationError("groupId", "그룹 공지에는 대상 그룹이 필요합니다."));
                }
                groupId = request.groupId();
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }

        if (scope == AnnouncementScope.ORG && !orgRepository.existsById(orgId)) {
            throw notFound("해당 기관이 존재하지 않습니다.");
        }
        if (scope == AnnouncementScope.GROUP) {
            // Unknown group and (for ORG_ADMIN) a group without resources in
            // their org answer the same 404 — group existence stays private.
            if (!groupRepository.existsById(groupId)
                    || (actor.role() == UserRole.ORG_ADMIN
                            && !groupLinkedToOrg(groupId, actor.orgId()))) {
                throw notFound("해당 그룹이 존재하지 않습니다.");
            }
        }

        // The 10/hour budget covers SENDS (contract: 발송 제한) — counted only
        // after every scope/gate/validation check passed, right before the
        // fan-out, so rejected attempts can never starve a valid announcement.
        // (REQUIRES_NEW: the count survives even if the fan-out tx rolls back
        // — an accepted send that fails mid-flight still spent its slot.)
        rateLimitService.hitHourly(RATE_SCOPE, String.valueOf(actor.id()), MAX_PER_HOUR);

        Announcement announcement = announcementRepository.saveAndFlush(new Announcement(
                actor.id(), scope, orgId, groupId, request.title().strip(), request.body().strip()));
        int recipients = fanOut(announcement);
        announcement.setRecipientCount(recipients);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.ANNOUNCEMENT_CREATE, "announcement", announcement.getId(),
                Map.of("scope", scope.name(), "recipientCount", recipients), ip);
        return AnnouncementView.from(announcement);
    }

    /**
     * Synchronous fan-out: one PENDING notifications row per ACTIVE user in
     * scope, in this transaction. Returns the actual insert count. ORG scope
     * resolves the canonical derived membership; GROUP scope reaches every
     * ACTIVE member of the (already gated) group.
     */
    private int fanOut(Announcement announcement) {
        // Event id and importance are bound from the NotificationEvent catalog
        // (single source) — the set-based INSERT…SELECT itself stays.
        String event = NotificationEvent.ANNOUNCEMENT.id();
        String importance = NotificationEvent.ANNOUNCEMENT.defaultImportance().name();
        String base = """
                insert into notifications
                    (user_id, event, title, body, importance, announcement_id, status)
                select u.id, ?, ?, ?, ?, ?, 'PENDING'
                  from users u
                """;
        return switch (announcement.getScope()) {
            case ALL -> jdbcTemplate.update(base + " where u.status = 'ACTIVE'",
                    event, announcement.getTitle(), announcement.getBody(), importance,
                    announcement.getId());
            case ORG -> jdbcTemplate.update(
                    base + " where u.status = 'ACTIVE' and (u.org_id = ? or "
                            + OrgMembershipSql.memberOfOrgLinkedGroup("u.id") + ")",
                    event, announcement.getTitle(), announcement.getBody(), importance,
                    announcement.getId(),
                    announcement.getOrgId(), announcement.getOrgId(), announcement.getOrgId());
            case GROUP -> jdbcTemplate.update(base + """
                          join group_members gm on gm.user_id = u.id
                         where gm.group_id = ? and u.status = 'ACTIVE'
                        """,
                    event, announcement.getTitle(), announcement.getBody(), importance,
                    announcement.getId(), announcement.getGroupId());
        };
    }

    /** The GROUP-scope gate: the group has resources in the caller's org. */
    private boolean groupLinkedToOrg(long groupId, Long orgId) {
        Boolean linked = jdbcTemplate.queryForObject(
                "select " + OrgMembershipSql.groupLinkedToOrg("?"),
                Boolean.class, groupId, orgId, groupId, orgId);
        return Boolean.TRUE.equals(linked);
    }

    private static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", detail);
    }
}
