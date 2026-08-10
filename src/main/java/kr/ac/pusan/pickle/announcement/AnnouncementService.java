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
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.orgs.Org;
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
 * org (mismatch 422); WORKSPACE scope is gated — for an ORG_ADMIN — on the workspace
 * having resources (requests / non-DELETED VMs) in their org (else 404,
 * existence masked); a gated workspace's recipients are all its ACTIVE members.
 * ORG-scope recipients follow the canonical <b>derived org membership</b>
 * ({@link OrgMembershipSql}) — ACTIVE members of org-linked workspaces plus the
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
    private final WorkspaceRepository workspaceRepository;
    private final OrgRepository orgRepository;
    private final JdbcTemplate jdbcTemplate;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;

    public AnnouncementService(AnnouncementRepository announcementRepository,
            WorkspaceRepository workspaceRepository, OrgRepository orgRepository,
            JdbcTemplate jdbcTemplate, RateLimitService rateLimitService,
            AuditService auditService) {
        this.announcementRepository = announcementRepository;
        this.workspaceRepository = workspaceRepository;
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
        Long workspaceId = null;
        // The scope target arrives as a public id; the row behind it is what the
        // announcement stores, and an id no row has is a validation error below.
        Long requestedOrgId = request.orgId() == null ? null
                : orgRepository.findByPublicId(request.orgId()).map(Org::getId).orElse(null);
        Long requestedWorkspaceId = request.workspaceId() == null ? null
                : workspaceRepository.findByPublicId(request.workspaceId())
                        .map(Workspace::getId).orElse(null);
        switch (scope) {
            case ALL -> {
                if (actor.role() != UserRole.SYS_ADMIN) {
                    throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                            "접근 권한이 없습니다", "전체 공지는 시스템 관리자만 발송할 수 있습니다.");
                }
                if (request.orgId() != null) {
                    errors.add(new FieldValidationError("orgId", "전체 공지에는 기관을 지정할 수 없습니다."));
                }
                if (request.workspaceId() != null) {
                    errors.add(new FieldValidationError("workspaceId", "전체 공지에는 워크스페이스를 지정할 수 없습니다."));
                }
            }
            case ORG -> {
                if (request.workspaceId() != null) {
                    errors.add(new FieldValidationError("workspaceId", "기관 공지에는 워크스페이스를 지정할 수 없습니다."));
                }
                if (actor.role() == UserRole.ORG_ADMIN) {
                    if (actor.orgId() == null) {
                        throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                                "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
                    }
                    if (request.orgId() != null && !actor.orgId().equals(requestedOrgId)) {
                        errors.add(new FieldValidationError("orgId",
                                "자기 기관에만 기관 공지를 발송할 수 있습니다."));
                    }
                    orgId = actor.orgId();
                } else {
                    if (request.orgId() == null) {
                        errors.add(new FieldValidationError("orgId", "기관 공지에는 대상 기관이 필요합니다."));
                    }
                    orgId = requestedOrgId;
                }
            }
            case WORKSPACE -> {
                if (request.orgId() != null) {
                    errors.add(new FieldValidationError("orgId", "워크스페이스 공지에는 기관을 지정할 수 없습니다."));
                }
                if (request.workspaceId() == null) {
                    errors.add(new FieldValidationError("workspaceId", "워크스페이스 공지에는 대상 워크스페이스가 필요합니다."));
                }
                workspaceId = requestedWorkspaceId;
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }

        if (scope == AnnouncementScope.ORG && (orgId == null || !orgRepository.existsById(orgId))) {
            throw notFound("해당 기관이 존재하지 않습니다.");
        }
        if (scope == AnnouncementScope.WORKSPACE) {
            // Unknown workspace and (for ORG_ADMIN) a workspace without resources in
            // their org answer the same 404 — workspace existence stays private.
            if (workspaceId == null || !workspaceRepository.existsByIdAndDeletedAtIsNull(workspaceId)
                    || (actor.role() == UserRole.ORG_ADMIN
                            && !workspaceLinkedToOrg(workspaceId, actor.orgId()))) {
                throw notFound("해당 워크스페이스가 존재하지 않습니다.");
            }
        }

        // The 10/hour budget covers SENDS (contract: 발송 제한) — counted only
        // after every scope/gate/validation check passed, right before the
        // fan-out, so rejected attempts can never starve a valid announcement.
        // (REQUIRES_NEW: the count survives even if the fan-out tx rolls back
        // — an accepted send that fails mid-flight still spent its slot.)
        rateLimitService.hitHourly(RATE_SCOPE, String.valueOf(actor.id()), MAX_PER_HOUR);

        Announcement announcement = announcementRepository.saveAndFlush(new Announcement(
                actor.id(), scope, orgId, workspaceId, request.title().strip(), request.body().strip()));
        int recipients = fanOut(announcement);
        announcement.setRecipientCount(recipients);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.ANNOUNCEMENT_CREATE, "announcement", announcement.getPublicId(),
                Map.of("scope", scope.name(), "recipientCount", recipients), ip);
        return AnnouncementView.from(announcement,
                request.orgId() == null ? null : request.orgId(),
                request.workspaceId() == null ? null : request.workspaceId());
    }

    /**
     * Synchronous fan-out: one PENDING notifications row per ACTIVE user in
     * scope, in this transaction. Returns the actual insert count. ORG scope
     * resolves the canonical derived membership; WORKSPACE scope reaches every
     * ACTIVE member of the (already gated) workspace.
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
                            + OrgMembershipSql.memberOfOrgLinkedWorkspace("u.id") + ")",
                    event, announcement.getTitle(), announcement.getBody(), importance,
                    announcement.getId(),
                    announcement.getOrgId(), announcement.getOrgId(), announcement.getOrgId());
            case WORKSPACE -> jdbcTemplate.update(base + """
                          join workspace_members gm on gm.user_id = u.id
                         where gm.workspace_id = ? and u.status = 'ACTIVE'
                        """,
                    event, announcement.getTitle(), announcement.getBody(), importance,
                    announcement.getId(), announcement.getWorkspaceId());
        };
    }

    /** The WORKSPACE-scope gate: the workspace has resources in the caller's org. */
    private boolean workspaceLinkedToOrg(long workspaceId, Long orgId) {
        Boolean linked = jdbcTemplate.queryForObject(
                "select " + OrgMembershipSql.workspaceLinkedToOrg("?"),
                Boolean.class, workspaceId, orgId, workspaceId, orgId);
        return Boolean.TRUE.equals(linked);
    }

    private static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", detail);
    }
}
