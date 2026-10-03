package kr.ac.pusan.pickle.announcement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import kr.ac.pusan.pickle.announcement.dto.AnnouncementCreateRequest;
import kr.ac.pusan.pickle.announcement.dto.AnnouncementPreviewResponse;
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
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.jspecify.annotations.Nullable;

/**
 * Announcement send (contract {@code createAnnouncement}). Scope rules:
 * ALL is SYS_ADMIN-only (403); an ORG_ADMIN's ORG scope is confined to the
 * organisations it <b>administers</b>, which it must name once there is more
 * than one (mismatch or omission 422); WORKSPACE scope is gated — for an
 * ORG_ADMIN — on the workspace having resources (requests / non-DELETED VMs) in
 * one of those (else 404, existence masked); a gated workspace's recipients are
 * all its ACTIVE members.
 *
 * <p>ORG-scope recipients follow the canonical <b>derived org membership</b>
 * ({@link OrgMembershipSql}) — ACTIVE members of org-linked workspaces — plus
 * the organisation's own administrators and operators. <b>A read-only role is
 * not a recipient</b>: a viewer is another organisation's staff looking in, not
 * a person this organisation announces to.
 *
 * <p>Fan-out inserts one resolved recipient snapshot into {@code notifications}
 * inside this transaction — the in-app rows exist when the 201 returns; email leaves
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
    @org.springframework.beans.factory.annotation.Autowired
    private kr.ac.pusan.pickle.mail.MailDeliveryJournal journal;

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
        Audience audience = resolveAudience(actor, request);
        // The existing budget covers accepted sends, never previews or rejected scope checks.
        rateLimitService.hitHourly(RATE_SCOPE, String.valueOf(actor.id()), MAX_PER_HOUR);
        Target target = audience.target();
        Announcement announcement = announcementRepository.saveAndFlush(new Announcement(
                actor.id(), target.scope(), target.orgId(), target.workspaceId(),
                request.title().strip(), request.body().strip()));
        List<Long> inserted = fanOut(announcement, audience.recipients());
        announcement.setRecipientCount(inserted.size());
        journal.importNotifications(inserted);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.ANNOUNCEMENT_CREATE, "announcement", announcement.getPublicId(),
                Map.of("scope", target.scope().name(), "recipientCount", inserted.size()), ip);
        return AnnouncementView.from(announcement, target.orgPublicId(), target.workspacePublicId());
    }

    /** A read-only estimate; sending resolves the current audience again before enqueue. */
    @Transactional(readOnly = true)
    public AnnouncementPreviewResponse preview(AuthenticatedUser actor, AnnouncementCreateRequest request) {
        Audience audience = resolveAudience(actor, request);
        List<AnnouncementPreviewResponse.AnnouncementRecipientSample> sample = audience.recipients().stream()
                .filter(recipient -> !actor.role().isOrgTier() || !recipient.role().isSysTier())
                .limit(20).map(recipient -> new AnnouncementPreviewResponse.AnnouncementRecipientSample(
                        recipient.publicId(), recipient.name(), recipient.email())).toList();
        Target target = audience.target();
        List<String> warnings = new ArrayList<>();
        if (audience.recipients().isEmpty()) {
            warnings.add("발송 대상자가 0명입니다. 알림과 메일이 생성되지 않습니다.");
        }
        if (actor.role().isOrgTier() && audience.recipients().stream().anyMatch(recipient -> recipient.role().isSysTier())) {
            warnings.add("일부 발송 대상자는 계정 열람 권한 때문에 예시에 표시되지 않습니다.");
        }
        return new AnnouncementPreviewResponse(target.scope(), target.orgPublicId(), target.workspacePublicId(),
                audience.recipients().size(), Instant.now(), sample, audience.recipients().size() > sample.size(),
                warnings);
    }

    private Audience resolveAudience(AuthenticatedUser actor, AnnouncementCreateRequest request) {
        Target target = resolveTarget(actor, request);
        return new Audience(target, recipients(target));
    }

    private Target resolveTarget(AuthenticatedUser actor, AnnouncementCreateRequest request) {
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
                    // The one place the actor's own organisation becomes a
                    // stored row, so it is the one place that has to ask which
                    // one when the account administers several. Naming it is
                    // required from the second organisation onwards; an account
                    // that administers exactly one still need not.
                    Set<Long> administered = actor.administeredOrgIds();
                    if (administered.isEmpty()) {
                        throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                                "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
                    }
                    if (request.orgId() != null) {
                        // An id no organisation has and one this account does not
                        // administer answer the same thing, so naming ids cannot
                        // be used to learn which organisations exist. The null
                        // test also has to come first: administeredOrgIds()
                        // returns an unmodifiable set, and those throw on
                        // contains(null) where a HashSet returns false — the
                        // collector is three lines away and invisible here, and
                        // this call answered 500 until 2026-08-26 because of it.
                        if (requestedOrgId == null || !administered.contains(requestedOrgId)) {
                            errors.add(new FieldValidationError("orgId",
                                    "자기 기관에만 기관 공지를 발송할 수 있습니다."));
                        }
                        orgId = requestedOrgId;
                    } else if (administered.size() == 1) {
                        orgId = administered.iterator().next();
                    } else {
                        errors.add(new FieldValidationError("orgId",
                                "기관 공지에는 대상 기관이 필요합니다."));
                    }
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
                            && !workspaceLinkedToOrg(workspaceId, actor.administeredOrgIds()))) {
                throw notFound("해당 워크스페이스가 존재하지 않습니다.");
            }
        }

        return new Target(scope, orgId, workspaceId,
                orgId == null ? null : orgRepository.findById(orgId).map(Org::getPublicId).orElse(null),
                workspaceId == null ? null : workspaceRepository.findById(workspaceId)
                        .map(Workspace::getPublicId).orElse(null));
    }

    /** One SQL snapshot supplies both a preview's count/sample and a send's fixed recipients. */
    private List<Recipient> recipients(Target target) {
        String base = """
                select u.id, u.public_id, u.name, u.email, u.role::text as account_role from users u
                """;
        List<Object> params = new ArrayList<>();
        String sql = switch (target.scope()) {
            case ALL -> base + " where u.status = 'ACTIVE'";
            case ORG -> {
                OrgScope scope = OrgScope.of(target.orgId());
                params.addAll(scope.orgIds());
                params.addAll(scope.orgIds());
                params.addAll(scope.orgIds());
                // A viewer role alone does not make somebody a recipient.
                yield base + " where u.status = 'ACTIVE' and (exists (select 1"
                        + " from user_org_roles uor where uor.user_id = u.id and "
                        + scope.inList("uor.org_id")
                        + " and uor.role::text in ('ORG_ADMIN', 'ORG_MANAGER')) or "
                        + OrgMembershipSql.memberOfOrgLinkedWorkspace("u.id", scope) + ")";
            }
            case WORKSPACE -> {
                params.add(target.workspaceId());
                yield base + " join workspace_members gm on gm.user_id = u.id"
                        + " where gm.workspace_id = ? and u.status = 'ACTIVE'";
            }
        };
        return jdbcTemplate.query(sql + " order by u.id", (rs, index) -> new Recipient(rs.getLong("id"),
                rs.getObject("public_id", UUID.class), rs.getString("name"), rs.getString("email"),
                UserRole.valueOf(rs.getString("account_role"))), params.toArray());
    }

    /** Bounded inserts reuse the already selected identities and addresses without another audience read. */
    private List<Long> fanOut(Announcement announcement, List<Recipient> recipients) {
        List<Long> ids = new ArrayList<>();
        for (int offset = 0; offset < recipients.size(); offset += 500) {
            List<Recipient> batch = recipients.subList(offset, Math.min(offset + 500, recipients.size()));
            String values = "(?, ?::text), ".repeat(batch.size() - 1) + "(?, ?::text)";
            List<Object> params = new ArrayList<>(List.of(NotificationEvent.ANNOUNCEMENT.id(),
                    announcement.getTitle(), announcement.getBody(),
                    NotificationEvent.ANNOUNCEMENT.defaultImportance().name(), announcement.getId()));
            for (Recipient recipient : batch) { params.add(recipient.id()); params.add(recipient.email()); }
            ids.addAll(jdbcTemplate.queryForList("""
                    insert into notifications (user_id, event, title, body, importance, announcement_id,
                        status, recipient_email)
                    select recipients.user_id, ?, ?, ?, ?, ?, 'PENDING', recipients.email
                      from (values %s) as recipients(user_id, email) returning id
                    """.formatted(values), Long.class, params.toArray()));
        }
        return ids;
    }

    private record Target(AnnouncementScope scope, @Nullable Long orgId, @Nullable Long workspaceId,
            @Nullable UUID orgPublicId, @Nullable UUID workspacePublicId) { }
    private record Recipient(long id, UUID publicId, String name, String email, UserRole role) { }
    private record Audience(Target target, List<Recipient> recipients) { }

    /** The WORKSPACE-scope gate: the workspace has resources in a managed org. */
    private boolean workspaceLinkedToOrg(long workspaceId, Collection<Long> orgIds) {
        OrgScope scope = OrgScope.of(orgIds);
        List<Object> params = new ArrayList<>();
        params.add(workspaceId);
        params.addAll(scope.orgIds());
        params.add(workspaceId);
        params.addAll(scope.orgIds());
        Boolean linked = jdbcTemplate.queryForObject(
                "select " + OrgMembershipSql.workspaceLinkedToOrg("?", scope),
                Boolean.class, params.toArray());
        return Boolean.TRUE.equals(linked);
    }

    private static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", detail);
    }
}
