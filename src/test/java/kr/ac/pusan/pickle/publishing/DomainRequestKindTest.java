package kr.ac.pusan.pickle.publishing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.publishing.dto.CreateDomainRequestSpec;
import kr.ac.pusan.pickle.request.RequestService;
import kr.ac.pusan.pickle.request.RequestStatus;
import kr.ac.pusan.pickle.request.dto.CreateRequestRequest;
import kr.ac.pusan.pickle.request.dto.RequestDetailResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Asking for a name through the request flow, under both policies.
 *
 * <p>The two routes are the same code past the decision, so what these tests
 * pin is the fork itself: which one a root sends a submission down, and that
 * the automatic side really does everything the reviewed side does rather than
 * only marking the row approved.</p>
 */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class DomainRequestKindTest {

    @Autowired
    private RequestService requestService;
    @Autowired
    private DomainRepository domainRepository;
    @Autowired
    private DnsDomainService domainService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long orgId;
    private UUID orgPublicId;
    private UUID workspacePublicId;
    private AuthenticatedUser applicant;

    @BeforeEach
    void setUp() {
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        jdbcTemplate.update("update orgs set status = 'ACTIVE' where id = ?", orgId);
        orgPublicId = jdbcTemplate.queryForObject("select public_id from orgs where id = ?",
                UUID.class, orgId);
        String slug = "drk-" + UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, slug);
        workspacePublicId = jdbcTemplate.queryForObject(
                "select public_id from workspaces where id = ?", UUID.class, workspaceId);
        long userId = jdbcTemplate.queryForObject("""
                insert into users (email, name, role, status, password_hash)
                values (?, ?, 'USER', 'ACTIVE', 'x') returning id
                """, Long.class, slug + "@pusan.ac.kr", "신청자");
        jdbcTemplate.update(
                "insert into workspace_members (workspace_id, user_id, role) values (?, ?, 'OWNER')",
                workspaceId, userId);
        applicant = new AuthenticatedUser(userId,
                jdbcTemplate.queryForObject("select public_id from users where id = ?", UUID.class,
                        userId),
                slug + "@pusan.ac.kr", UserRole.USER, Map.of());
        jdbcTemplate.update("delete from domain_roots");
    }

    // Labels carry this class's own prefix. The suite shares one database and
    // the surface test issues names of its own; a collision between two test
    // classes would read as this one's guard firing correctly.
    private void root(String rootDomain, boolean autoApprove) {
        jdbcTemplate.update(
                "insert into domain_roots (root_domain, org_id, auto_approve) values (?, ?, ?)",
                rootDomain, orgId, autoApprove);
    }

    @Test
    void aFormThatNamesNoPeriodIsAcceptedBecauseThisKindHasItsOwn() {
        root("pusan.dev", true);

        // Every other kind is refused for leaving the period out. A name's life
        // is its renewal deadline, so the wizard does not show the control —
        // and a submission that therefore carries none has to be accepted, or
        // the screen is blocked on a field it was told to hide.
        CreateRequestRequest noPeriod = new CreateRequestRequest(ResourceType.DOMAIN,
                workspacePublicId, orgPublicId, "사유", null, null, null, null, "reqnoperiod",
                null, null, null, new CreateDomainRequestSpec("reqnoperiod", "pusan.dev"));

        RequestDetailResponse created = requestService.create(applicant, noPeriod, "127.0.0.1");

        assertThat(created.status()).isEqualTo(RequestStatus.APPROVED);
        // And nothing stored a date beside the deadline that actually ends it.
        assertThat(jdbcTemplate.queryForObject(
                "select req_end_date from requests where public_id = ?",
                java.sql.Date.class, created.id())).isNull();
    }

    @Test
    void aWorkspaceAtItsCapIsRefusedAtSubmissionRatherThanAtApproval() {
        root("pusan.dev", false);
        for (int i = 0; i < 5; i++) {
            requestService.create(applicant, form("reqcap" + i), "127.0.0.1");
        }

        // Queued requests count. Without that, five pending all pass and the
        // reviewer meets the cap one approval at a time, for a conflict the
        // applicant caused and the reviewer cannot resolve.
        assertThat(fieldErrorsOf(() -> requestService.create(applicant, form("reqcapmore"),
                "127.0.0.1"))).anyMatch(error -> error.message().contains("다 썼습니다"));
    }

    @Test
    void theOwnerOfAReservedNameIsSentToTheDoorThatRecoversIt() {
        root("pusan.dev", true);
        RequestDetailResponse created = requestService.create(applicant, form("reqcomeback"),
                "127.0.0.1");
        UUID domainId = domainRepository
                .findFirstByFqdnAndStatusNot("reqcomeback.pusan.dev", DomainStatus.REMOVED)
                .orElseThrow().getPublicId();
        domainService.delete(applicant, domainId, "127.0.0.1");

        // The generic collision message would tell them to pick a different
        // name — for a name that is still theirs and that they can have back.
        assertThat(fieldErrorsOf(() -> requestService.create(applicant, form("reqcomeback"),
                "127.0.0.1"))).anyMatch(error -> error.message().contains("되살려"));
    }

    /** The field errors a refused submission carries, not the generic title. */
    private java.util.List<kr.ac.pusan.pickle.common.error.FieldValidationError> fieldErrorsOf(
            Runnable submission) {
        try {
            submission.run();
        } catch (ApiException refused) {
            return refused.getErrors();
        }
        throw new AssertionError("submission was accepted");
    }

    private CreateRequestRequest form(String label) {
        return new CreateRequestRequest(ResourceType.DOMAIN, workspacePublicId, orgPublicId,
                "수업 과제 사이트", null, null, null, true, label,
                null, null, null, new CreateDomainRequestSpec(label, "pusan.dev"));
    }

    @Test
    void anOpenRootIssuesTheNameWithTheSubmission() {
        root("pusan.dev", true);

        RequestDetailResponse created = requestService.create(applicant, form("reqmysite"),
                "127.0.0.1");

        assertThat(created.status()).isEqualTo(RequestStatus.APPROVED);
        // The name exists. A status flipped to approved with nothing behind it
        // is the failure this asserts against — the request flow's own guard
        // refuses that at commit, and this says so from the outside too.
        assertThat(domainRepository.findFirstByFqdnAndStatusNot("reqmysite.pusan.dev",
                DomainStatus.REMOVED)).isPresent();
        // And its requester owns it, which is the step that makes it reachable.
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from resource_access_grants g
                  join domains d on d.id = g.resource_id
                 where g.resource_type = 'DOMAIN'::resource_type and g.role = 'OWNER'
                   and g.user_id = ? and d.fqdn = 'reqmysite.pusan.dev'
                """, Long.class, applicant.id())).isEqualTo(1);
    }

    @Test
    void anAutomaticApprovalRecordsThatNobodyDecidedIt() {
        root("pusan.dev", true);

        RequestDetailResponse created = requestService.create(applicant, form("reqnobody"),
                "127.0.0.1");

        // The review row exists — leaving it out would show the applicant an
        // approved request with nothing saying when, and would take the
        // approved-request guard out of the loop for this whole kind.
        assertThat(created.review()).isNotNull();
        // An APPROVE, not just any decision. A reject row with a null reviewer
        // would satisfy "nobody decided it" and mean the opposite.
        assertThat(created.review().decision())
                .isEqualTo(kr.ac.pusan.pickle.request.ReviewDecision.APPROVE);
        assertThat(created.review().reviewerId()).isNull();
        // And it does not claim a person. Naming the applicant would read as
        // self-approval; falling through to the withdrawn-member literal would
        // name someone who never existed.
        assertThat(created.review().reviewerName()).isEqualTo("자동 승인");
        assertThat(jdbcTemplate.queryForObject("""
                select reviewer_id from request_reviews rv join requests r on r.id = rv.request_id
                 where r.public_id = ?
                """, Long.class, created.id())).isNull();
    }

    @Test
    void aReviewedRootLeavesTheRequestWaitingAndTheNameUnmade() {
        root("pusan.dev", false);

        RequestDetailResponse created = requestService.create(applicant, form("reqwaits"),
                "127.0.0.1");

        assertThat(created.status()).isEqualTo(RequestStatus.SUBMITTED);
        assertThat(created.review()).isNull();
        // The detail row is written at submission, not at approval — it is what
        // the reviewer reads to decide, and what the second submission of the
        // same label is refused against.
        assertThat(created.domain()).isNotNull();
        assertThat(created.domain().label()).isEqualTo("reqwaits");
        assertThat(created.domain().grantedFqdn()).isNull();
        // Nothing is created until somebody decides. A name made at submission
        // and taken away on rejection would be visible in DNS in between.
        assertThat(domainRepository.findFirstByFqdnAndStatusNot("reqwaits.pusan.dev",
                DomainStatus.REMOVED)).isEmpty();
    }

    @Test
    void theOrganisationComesFromTheRootEvenWhenTheFormSaysOtherwise() {
        long otherOrgId = jdbcTemplate.queryForObject(
                "insert into orgs (name, status) values (?, 'ACTIVE') returning id",
                Long.class, "다른 기관 " + UUID.randomUUID().toString().substring(0, 8));
        UUID otherOrgPublicId = jdbcTemplate.queryForObject(
                "select public_id from orgs where id = ?", UUID.class, otherOrgId);
        root("pusan.dev", true);

        // The applicant names one institution and the root names another. Two
        // answers about whose a name is would leave the administrator listing
        // scoped on one of them and the name belonging to the other.
        CreateRequestRequest mismatched = new CreateRequestRequest(ResourceType.DOMAIN,
                workspacePublicId, otherOrgPublicId, "사유", null, null, null, true, "reqfromroot",
                null, null, null, new CreateDomainRequestSpec("reqfromroot", "pusan.dev"));
        RequestDetailResponse created = requestService.create(applicant, mismatched, "127.0.0.1");

        assertThat(jdbcTemplate.queryForObject(
                "select org_id from requests where public_id = ?", Long.class, created.id()))
                .isEqualTo(orgId);
        assertThat(jdbcTemplate.queryForObject(
                "select org_id from domains where fqdn = 'reqfromroot.pusan.dev'", Long.class))
                .isEqualTo(orgId);
    }

    @Test
    void aSecondRequestForTheSameNameIsRefusedAtSubmission() {
        root("pusan.dev", false);
        requestService.create(applicant, form("reqcontested"), "127.0.0.1");

        // Nothing reserves a name between submission and approval, so without
        // this both would pass and the second approval would fail on the unique
        // index — in front of a reviewer, for a mistake they did not make.
        assertThat(fieldErrorsOf(() -> requestService.create(applicant, form("reqcontested"),
                "127.0.0.1"))).anyMatch(error -> error.field().equals("domain.label")
                        && error.message().contains("검토를 기다리고"));
    }

    @Test
    void aNameAlreadyInTheGroundIsRefusedAtSubmission() {
        root("pusan.dev", true);
        requestService.create(applicant, form("reqtaken"), "127.0.0.1");

        assertThat(fieldErrorsOf(() -> requestService.create(applicant, form("reqtaken"),
                "127.0.0.1"))).anyMatch(error -> error.field().equals("domain.label")
                        && error.message().contains("이미 사용 중"));
    }

    @Test
    void aRootWithNoRowIssuesNothingEvenWhenTheSettingAllowsIt() {
        // The setting says issuing is permitted under this root; the missing
        // row says the platform does not know whose the names would be. The
        // organisation is the whole reason the table exists.
        // Named, not merely refused: a form rejected for some unrelated field
        // would satisfy "it threw" and say nothing about the root.
        assertThat(fieldErrorsOf(() -> requestService.create(applicant, form("reqorphan"),
                "127.0.0.1"))).anyMatch(error -> error.field().equals("domain.rootDomain"));
        assertThat(domainRepository.findFirstByFqdnAndStatusNot("reqorphan.pusan.dev",
                DomainStatus.REMOVED)).isEmpty();
    }

    @Test
    void theIssuedNameIsRecordedOnTheRequestItCameFrom() {
        root("pusan.dev", true);

        RequestDetailResponse created = requestService.create(applicant, form("reqrecorded"),
                "127.0.0.1");

        // Not the two asked-for fields joined back together: a root's default
        // can move between submission and approval, so which name was actually
        // made is its own fact.
        assertThat(created.domain()).isNotNull();
        assertThat(created.domain().label()).isEqualTo("reqrecorded");
        assertThat(created.domain().grantedFqdn()).isEqualTo("reqrecorded.pusan.dev");
        assertThat(domainRepository.findFirstByFqdnAndStatusNot("reqrecorded.pusan.dev",
                DomainStatus.REMOVED)).get()
                .extracting(Domain::getRenewDueAt).matches(due -> ((Instant) due).isAfter(Instant.now()));
    }
}
