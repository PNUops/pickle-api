package kr.ac.pusan.pickle.access;

import static kr.ac.pusan.pickle.support.AccessGrantFixtures.grantVmToOwningGroup;
import static kr.ac.pusan.pickle.support.AccessGrantFixtures.grantVmToUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.inventory.VmFlavor;
import kr.ac.pusan.pickle.inventory.VmFlavorRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * The VM access list surface (contract tag {@code vm-access}): who may read and
 * edit one VM's list, which grants the list will accept, and what the edits
 * leave behind in the audit trail.
 *
 * <p>Two axes meet on this surface and the tests keep them apart on purpose.
 * The resource axis is the list itself — a rung there is the only thing that
 * lets anyone act inside a VM. The group axis contributes exactly one thing: an
 * owner of the owning group manages the list whether or not they appear on it,
 * which is the recovery path for a VM whose own owner is gone. Because that
 * standing can be used to hand its holder real access, the edits that do so are
 * marked in the audit trail as break-glass, and several tests below exist only
 * to pin the precise boundary of that marker.
 *
 * <p>Every VM here is inserted straight through {@link JdbcTemplate} and so
 * never passed through approval, which is what would normally write the first
 * grant; {@link #createVm} therefore names its owner explicitly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class VmAccessGrantApiTest {

    /** Unique proxmox_vmid per created VM (the column is globally unique). */
    private static final AtomicInteger VMID_SEQ = new AtomicInteger(985_000);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OsImageRepository imageRepository;
    @Autowired
    private VmFlavorRepository flavorRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Owner of the group that owns every VM here; deliberately never granted. */
    private User groupOwner;
    /** Holds the OWNER rung on each VM, which is the ordinary way to manage a list. */
    private User vmOwner;
    /** In the owning group, on no VM's list — the honest 403 case. */
    private User member;
    /** In the owning group, granted VIEWER where a test needs a below-MEMBER rung. */
    private User viewer;
    /** Outside the owning group — the masked 404 case. */
    private User outsider;

    private String groupOwnerToken;
    private String vmOwnerToken;
    private String memberToken;
    private String viewerToken;
    private String outsiderToken;
    private String orgAdminToken;

    private long orgId;
    private long nodeId;
    private OsImage image;
    private VmFlavor flavor;
    private long groupId;

    @BeforeEach
    void setUp() throws Exception {
        groupOwner = ensureUser("vmacc.groupowner@pusan.ac.kr", "그룹소유자");
        vmOwner = ensureUser("vmacc.vmowner@pusan.ac.kr", "자원소유자");
        member = ensureUser("vmacc.member@pusan.ac.kr", "일반구성원");
        viewer = ensureUser("vmacc.viewer@pusan.ac.kr", "열람자");
        outsider = ensureUser("vmacc.outsider@pusan.ac.kr", "외부인");
        groupOwnerToken = jwtService.createAccessToken(groupOwner);
        vmOwnerToken = jwtService.createAccessToken(vmOwner);
        memberToken = jwtService.createAccessToken(member);
        viewerToken = jwtService.createAccessToken(viewer);
        outsiderToken = jwtService.createAccessToken(outsider);
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow());

        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        image = imageRepository.findAll().stream()
                .filter(candidate -> candidate.getName().equals("ubuntu-24.04")
                        && candidate.getStatus() == CatalogStatus.ACTIVE)
                .findFirst().orElseThrow();
        flavor = flavorRepository.findAll().stream()
                .filter(candidate -> candidate.getName().equals("basic"))
                .findFirst().orElseThrow();

        // A fresh group per test: several tests remove people from it, and a
        // group carried across methods would make that removal order-dependent.
        groupId = createTeam("vmacc-" + UUID.randomUUID().toString().substring(0, 8));
        addMember(groupId, vmOwner.getEmail());
        addMember(groupId, member.getEmail());
        addMember(groupId, viewer.getEmail());
    }

    // ── who may manage the list ────────────────────────────────────────────

    /**
     * The management gate, including the distinction the masking policy turns
     * on: a member of the owning group is told the VM exists and refused (403),
     * while an outsider is not told that much (404).
     */
    @Test
    void onlyTheResourceOwnerAndTheGroupOwnerManageTheList() throws Exception {
        long vmId = createVm();

        // the VM's own owner reads the list and sees the entry that made them one
        listGrants(vmOwnerToken, vmId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grants.length()").value(1))
                // The list names its VM so that whoever may manage it without
                // being able to open it still knows what they are deciding about.
                .andExpect(jsonPath("$.vm.id").value((int) vmId))
                .andExpect(jsonPath("$.vm.groupId").value((int) groupId))
                .andExpect(jsonPath("$.grants[0].granteeType").value("USER"))
                .andExpect(jsonPath("$.grants[0].user.userId").value(vmOwner.getId().intValue()))
                .andExpect(jsonPath("$.grants[0].user.name").value("자원소유자"))
                .andExpect(jsonPath("$.grants[0].role").value("OWNER"));

        grantVmToUser(jdbcTemplate, vmId, viewer.getId(), "VIEWER");

        // the recovery path: a group owner with no grant at all manages it too
        listGrants(groupOwnerToken, vmId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grants.length()").value(2));

        // a plain member of the owning group, and a VIEWER grant, are both refused
        // in the open — they can already see the VM listed, so hiding it would lie
        listGrants(memberToken, vmId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));
        listGrants(viewerToken, vmId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));

        // an outsider is not told the VM exists
        listGrants(outsiderToken, vmId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // the writes are guarded by the very same split
        addGrant(memberToken, vmId, userGrant(member.getId(), "MEMBER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));
        addGrant(outsiderToken, vmId, userGrant(member.getId(), "MEMBER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // an id that names no VM is the same 404, for a manager as for anyone
        listGrants(vmOwnerToken, 999_999)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // ── what the list will accept ──────────────────────────────────────────

    /**
     * A named grant may only reach an active member of the owning group. The
     * rule is what keeps the list from contradicting the 404 that hides the VM:
     * a row for someone the VM is invisible to would be a grant nobody can use.
     */
    @Test
    void aUserGrantOnlyNamesAnActiveMemberOfTheOwningGroup() throws Exception {
        long vmId = createVm();

        // somebody outside the owning group cannot be put on the list
        addGrant(vmOwnerToken, vmId, userGrant(outsider.getId(), "MEMBER"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("userId"));

        // nor can a member whose account is no longer active
        User disabled = ensureUser("vmacc.disabled-" + UUID.randomUUID() + "@pusan.ac.kr", "비활성구성원");
        addMember(groupId, disabled.getEmail());
        jdbcTemplate.update("update users set status = 'DISABLED'::user_status where id = ?",
                disabled.getId());
        addGrant(vmOwnerToken, vmId, userGrant(disabled.getId(), "MEMBER"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("userId"));

        // a USER grant that names nobody is the same field error, not a group grant
        Map<String, Object> nameless = new HashMap<>();
        nameless.put("granteeType", "USER");
        nameless.put("role", "MEMBER");
        addGrant(vmOwnerToken, vmId, nameless)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("userId"));

        // none of the refusals wrote a row, and an eligible member is accepted
        assertThat(grantCount(vmId)).isEqualTo(1);
        addGrant(vmOwnerToken, vmId, userGrant(member.getId(), "EDITOR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.granteeType").value("USER"))
                .andExpect(jsonPath("$.user.userId").value(member.getId().intValue()))
                .andExpect(jsonPath("$.role").value("EDITOR"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        assertThat(grantCount(vmId)).isEqualTo(2);
    }

    /**
     * A group-wide grant is capped below the rungs that configure or command a
     * VM, on the way in and on every later change of rung — a PATCH that could
     * raise it afterwards would make the cap decorative.
     */
    @Test
    void aGroupWideGrantIsCappedAtMemberOnAddAndOnUpdate() throws Exception {
        long vmId = createVm();

        addGrant(vmOwnerToken, vmId, groupGrant("OWNER"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("role"));
        addGrant(vmOwnerToken, vmId, groupGrant("EDITOR"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("role"));

        // the two permitted rungs go in, and a group-wide row names nobody
        long grantId = addGrantId(vmOwnerToken, vmId, groupGrant("MEMBER"));
        listGrants(vmOwnerToken, vmId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grants[1].granteeType").value("GROUP"))
                .andExpect(jsonPath("$.grants[1].user").value((Object) null))
                .andExpect(jsonPath("$.grants[1].role").value("MEMBER"));

        // the cap holds on PATCH …
        updateGrant(vmOwnerToken, vmId, grantId, "EDITOR")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("role"));
        updateGrant(vmOwnerToken, vmId, grantId, "OWNER")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("role"));
        // … and the refused PATCHes left the stored rung alone
        assertThat(roleOfGrant(grantId)).isEqualTo("MEMBER");

        updateGrant(vmOwnerToken, vmId, grantId, "VIEWER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));
        assertThat(roleOfGrant(grantId)).isEqualTo("VIEWER");

        // a named grant is under no such cap
        addGrant(vmOwnerToken, vmId, userGrant(member.getId(), "EDITOR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("EDITOR"));
    }

    /**
     * One entry per person and at most one for the group: a second entry for the
     * same target is a conflict rather than a silent second opinion on their rung.
     */
    @Test
    void aSecondEntryForTheSameTargetIsAConflict() throws Exception {
        long vmId = createVm();

        addGrant(vmOwnerToken, vmId, userGrant(member.getId(), "MEMBER"))
                .andExpect(status().isCreated());
        // same person again — even at a different rung, which is what makes the
        // conflict a conflict rather than an update
        addGrant(vmOwnerToken, vmId, userGrant(member.getId(), "VIEWER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_ACCESS_GRANT_EXISTS"));

        addGrant(vmOwnerToken, vmId, groupGrant("MEMBER"))
                .andExpect(status().isCreated());
        addGrant(vmOwnerToken, vmId, groupGrant("VIEWER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_ACCESS_GRANT_EXISTS"));

        // three rows: the seeded owner, the one member, the one group-wide entry
        assertThat(grantCount(vmId)).isEqualTo(3);
        assertThat(roleOfUserGrant(vmId, member.getId())).isEqualTo("MEMBER");
    }

    // ── how the two grants combine ─────────────────────────────────────────

    /**
     * The rung someone acts at is the higher of their own grant and the
     * group-wide one, proved by an action rather than by reading rows back: a
     * VIEWER who is refused power control gains it from a group-wide MEMBER
     * grant, while their own row still says VIEWER.
     */
    @Test
    void theEffectiveRungIsTheHigherOfTheOwnAndGroupWideGrant() throws Exception {
        long vmId = createVm();
        grantVmToUser(jdbcTemplate, vmId, viewer.getId(), "VIEWER");

        // VIEWER alone: the VM is visible, and nothing inside it is reachable
        mockMvc.perform(get("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myResourceRole").value("VIEWER"))
                .andExpect(jsonPath("$.powerControlAllowed").value(false));
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/start")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));

        // the group-wide grant raises everyone in the group, this person included
        grantVmToOwningGroup(jdbcTemplate, vmId, "MEMBER");
        mockMvc.perform(get("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myResourceRole").value("MEMBER"))
                .andExpect(jsonPath("$.powerControlAllowed").value(true));
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/start")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isAccepted());

        // the maximum is computed, not written back: the personal row is untouched
        assertThat(roleOfUserGrant(vmId, viewer.getId())).isEqualTo("VIEWER");

        // and the group-wide grant reaches only the owning group — an outsider
        // is still not told the VM exists
        mockMvc.perform(get("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
    }

    // ── break-glass auditing ───────────────────────────────────────────────

    /**
     * A group owner holds no way inside a VM until they edit the list, so the
     * edits that put them inside are recorded twice: once as the ordinary
     * change, once as a break-glass event. Revocation never qualifies.
     */
    @Test
    void aGroupOwnerLettingThemselvesInIsRecordedAsBreakGlass() throws Exception {
        long vmId = createVm();

        // granting themselves a rung that reaches the VM's contents
        long selfGrantId = addGrantId(groupOwnerToken, vmId,
                userGrant(groupOwner.getId(), "MEMBER"));
        assertThat(auditCount(AuditService.VM_ACCESS_GRANT_ADD, vmId)).isEqualTo(1);
        assertThat(auditCount(AuditService.VM_ACCESS_BREAK_GLASS, vmId)).isEqualTo(1);
        // the marker carries the same detail as the change it shadows
        assertThat(auditDetail(AuditService.VM_ACCESS_BREAK_GLASS, vmId, "grantId"))
                .isEqualTo(String.valueOf(selfGrantId));
        assertThat(auditDetail(AuditService.VM_ACCESS_BREAK_GLASS, vmId, "role"))
                .isEqualTo("MEMBER");

        // A group-wide grant is included deliberately: without it the same
        // result could be reached without the marker. It needs a VM of its own
        // — on the one above the self-grant already put them inside, and an
        // actor who is already inside opens no door.
        long groupWideVm = createVm();
        addGrant(groupOwnerToken, groupWideVm, groupGrant("MEMBER"))
                .andExpect(status().isCreated());
        assertThat(auditCount(AuditService.VM_ACCESS_GRANT_ADD, groupWideVm)).isEqualTo(1);
        assertThat(auditCount(AuditService.VM_ACCESS_BREAK_GLASS, groupWideVm)).isEqualTo(1);

        // taking access away opens no door, whoever it belongs to
        deleteGrant(groupOwnerToken, vmId, selfGrantId)
                .andExpect(status().isNoContent());
        assertThat(auditCount(AuditService.VM_ACCESS_GRANT_REMOVE, vmId)).isEqualTo(1);
        assertThat(auditCount(AuditService.VM_ACCESS_BREAK_GLASS, vmId)).isEqualTo(1);
    }

    /**
     * The boundary of the marker: it fires on the actor ending up at MEMBER or
     * above, so a group owner parking themselves at VIEWER — which reaches
     * nothing inside the VM — is an ordinary edit, and the later raise is what
     * trips it.
     */
    @Test
    void aSelfGrantBelowMemberIsNotBreakGlass() throws Exception {
        long vmId = createVm();

        // VIEWER is no more than the standing read a group owner already had
        long selfGrantId = addGrantId(groupOwnerToken, vmId,
                userGrant(groupOwner.getId(), "VIEWER"));
        assertThat(auditCount(AuditService.VM_ACCESS_GRANT_ADD, vmId)).isEqualTo(1);
        assertThat(auditCount(AuditService.VM_ACCESS_BREAK_GLASS, vmId)).isZero();

        // and neither is a group-wide grant that stops at the same rung
        addGrant(groupOwnerToken, vmId, groupGrant("VIEWER"))
                .andExpect(status().isCreated());
        assertThat(auditCount(AuditService.VM_ACCESS_GRANT_ADD, vmId)).isEqualTo(2);
        assertThat(auditCount(AuditService.VM_ACCESS_BREAK_GLASS, vmId)).isZero();

        // raising their own rung to one that reaches the contents is the event
        updateGrant(groupOwnerToken, vmId, selfGrantId, "MEMBER")
                .andExpect(status().isOk());
        assertThat(auditCount(AuditService.VM_ACCESS_GRANT_UPDATE, vmId)).isEqualTo(1);
        assertThat(auditCount(AuditService.VM_ACCESS_BREAK_GLASS, vmId)).isEqualTo(1);
        assertThat(auditDetail(AuditService.VM_ACCESS_GRANT_UPDATE, vmId, "previousRole"))
                .isEqualTo("VIEWER");
    }

    /**
     * The marker means one thing: someone who could not reach the contents let
     * themselves in. An actor who already held them is not that, and neither is
     * an actor reducing their own rung — recording either would spend the
     * signal's credibility on routine list-keeping, and an investigator who has
     * learned to discount break-glass rows has lost the only thing they are for.
     */
    @Test
    void anActorAlreadyInsideNeverTripsBreakGlassNotEvenWhenSteppingDown() throws Exception {
        long vmId = createVm();
        // A resource owner who is only a plain member of the group: they hold no
        // standing rights, so no edit of theirs can be an escape hatch.
        grantVmToUser(jdbcTemplate, vmId, member.getId(), "OWNER");
        long ownGrantId = userGrantId(vmId, member.getId());

        // opening the VM to the whole group is ordinary list-keeping for them
        addGrant(memberToken, vmId, groupGrant("MEMBER"))
                .andExpect(status().isCreated());
        assertThat(auditCount(AuditService.VM_ACCESS_GRANT_ADD, vmId)).isEqualTo(1);
        assertThat(auditCount(AuditService.VM_ACCESS_BREAK_GLASS, vmId)).isZero();

        // and stepping their own rung down is the opposite of breaking glass,
        // even though they are still above the line the predicate looks at
        updateGrant(memberToken, vmId, ownGrantId, "MEMBER")
                .andExpect(status().isOk());
        assertThat(auditCount(AuditService.VM_ACCESS_GRANT_UPDATE, vmId)).isEqualTo(1);
        assertThat(auditCount(AuditService.VM_ACCESS_BREAK_GLASS, vmId)).isZero();
        assertThat(roleOfGrant(ownGrantId)).isEqualTo("MEMBER");
    }

    // ── lifecycle cleanup ──────────────────────────────────────────────────

    /**
     * A grant may only name a member of the owning group, so leaving the group
     * takes the grants with it rather than leaving rows a rejoin would silently
     * restore. The removal audit says how many went.
     */
    @Test
    void leavingTheOwningGroupTakesThatPersonsGrants() throws Exception {
        long firstVm = createVm();
        long secondVm = createVm();
        grantVmToUser(jdbcTemplate, firstVm, member.getId(), "EDITOR");
        grantVmToUser(jdbcTemplate, secondVm, member.getId(), "MEMBER");
        grantVmToUser(jdbcTemplate, firstVm, viewer.getId(), "VIEWER");

        // removed by the group owner: both grants go, and the audit counts them
        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + member.getId())
                        .header("Authorization", "Bearer " + groupOwnerToken)
                        .header(ReauthTestSupport.HEADER, reauth(groupOwnerToken)))
                .andExpect(status().isNoContent());
        assertThat(userGrantCountInGroup(member.getId(), groupId)).isZero();
        assertThat(groupAuditDetail(AuditService.GROUP_MEMBER_REMOVE, groupId, "revokedGrants"))
                .isEqualTo("2");

        // the VM they were an EDITOR of is now invisible to them, not merely closed
        mockMvc.perform(get("/api/v1/vms/" + firstVm)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());

        // a withdrawal of one's own accord is the same cleanup
        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + viewer.getId())
                        .header("Authorization", "Bearer " + viewerToken)
                        .header(ReauthTestSupport.HEADER, reauth(viewerToken)))
                .andExpect(status().isNoContent());
        assertThat(userGrantCountInGroup(viewer.getId(), groupId)).isZero();
        assertThat(groupAuditDetail(AuditService.GROUP_MEMBER_REMOVE, groupId, "revokedGrants"))
                .isEqualTo("1");
        assertThat(groupAuditDetail(AuditService.GROUP_MEMBER_REMOVE, groupId, "selfLeave"))
                .isEqualTo("true");

        // the VM's own owner is untouched by either departure
        assertThat(roleOfUserGrant(firstVm, vmOwner.getId())).isEqualTo("OWNER");
    }

    /**
     * Approval is what writes a VM's first grant, and it names the requester —
     * so a requester who can no longer hold one makes the request unapprovable
     * rather than making the platform guess whose VM it is.
     */
    @Test
    void approvalRefusesARequesterWhoCanNoLongerHoldAGrant() throws Exception {
        // (a) the requester left the group between submitting and the decision
        User departing = groupMember("vmacc.departing", "탈퇴신청자");
        long departedRequest = submitRequest(jwtService.createAccessToken(departing));
        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + departing.getId())
                        .header("Authorization", "Bearer " + groupOwnerToken)
                        .header(ReauthTestSupport.HEADER, reauth(groupOwnerToken)))
                .andExpect(status().isNoContent());
        approveRequest(departedRequest)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_REQUESTER_INELIGIBLE"));

        // (b) still a member, but the account is no longer active
        User suspended = groupMember("vmacc.suspended", "비활성신청자");
        long suspendedRequest = submitRequest(jwtService.createAccessToken(suspended));
        jdbcTemplate.update("update users set status = 'DISABLED'::user_status where id = ?",
                suspended.getId());
        approveRequest(suspendedRequest)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_REQUESTER_INELIGIBLE"));

        // the refusal rolled everything back: no VM, and the request stays
        // decidable so the reviewer can reject it instead
        assertThat(vmCountForRequest(departedRequest)).isZero();
        assertThat(vmCountForRequest(suspendedRequest)).isZero();
        mockMvc.perform(get("/api/v1/admin/vm-requests/" + departedRequest)
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    // ── addressing one entry ───────────────────────────────────────────────

    /**
     * A grant id is only meaningful under the VM it belongs to: one borrowed
     * from a neighbouring VM is as absent as one that was never issued, so the
     * path cannot be used to edit another VM's list.
     */
    @Test
    void aGrantIdFromAnotherVmOrNoVmAtAllIsNotFound() throws Exception {
        long vmId = createVm();
        long otherVmId = createVm();
        long foreignGrantId = addGrantId(vmOwnerToken, otherVmId,
                userGrant(member.getId(), "MEMBER"));

        updateGrant(vmOwnerToken, vmId, foreignGrantId, "VIEWER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        deleteGrant(vmOwnerToken, vmId, foreignGrantId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        updateGrant(vmOwnerToken, vmId, 999_999_999L, "VIEWER")
                .andExpect(status().isNotFound());
        deleteGrant(vmOwnerToken, vmId, 999_999_999L)
                .andExpect(status().isNotFound());

        // the neighbour's list survived every one of those attempts
        assertThat(roleOfGrant(foreignGrantId)).isEqualTo("MEMBER");
        assertThat(grantCount(otherVmId)).isEqualTo(2);
    }

    /**
     * Every edit of an access list is sudo-mode gated: authorization alone is
     * not enough without a fresh proof of identity. Reading the list is not.
     */
    @Test
    void theWriteOperationsNeedSudoMode() throws Exception {
        long vmId = createVm();
        long grantId = addGrantId(vmOwnerToken, vmId, userGrant(member.getId(), "MEMBER"));

        mockMvc.perform(post(accessPath(vmId))
                        .header("Authorization", "Bearer " + vmOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userGrant(viewer.getId(), "VIEWER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"))
                .andExpect(jsonPath("$.title").value("재인증이 필요합니다"));
        mockMvc.perform(patch(accessPath(vmId) + "/" + grantId)
                        .header("Authorization", "Bearer " + vmOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "VIEWER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
        mockMvc.perform(delete(accessPath(vmId) + "/" + grantId)
                        .header("Authorization", "Bearer " + vmOwnerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
        // a garbage header is no better than none
        mockMvc.perform(delete(accessPath(vmId) + "/" + grantId)
                        .header("Authorization", "Bearer " + vmOwnerToken)
                        .header(ReauthTestSupport.HEADER, "not-a-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));

        // nothing changed, and reading the list never needed the header
        assertThat(roleOfGrant(grantId)).isEqualTo("MEMBER");
        assertThat(grantCount(vmId)).isEqualTo(2);
        listGrants(vmOwnerToken, vmId).andExpect(status().isOk());
    }

    // ── request helpers ────────────────────────────────────────────────────

    private static String accessPath(long vmId) {
        return "/api/v1/vms/" + vmId + "/access";
    }

    private ResultActions listGrants(String token, long vmId) throws Exception {
        return mockMvc.perform(get(accessPath(vmId))
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions addGrant(String token, long vmId, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post(accessPath(vmId))
                .header("Authorization", "Bearer " + token)
                .header(ReauthTestSupport.HEADER, reauth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** Adds a grant that is expected to be accepted and returns its id. */
    private long addGrantId(String token, long vmId, Map<String, Object> body) throws Exception {
        String response = addGrant(token, vmId, body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private ResultActions updateGrant(String token, long vmId, long grantId, String role)
            throws Exception {
        return mockMvc.perform(patch(accessPath(vmId) + "/" + grantId)
                .header("Authorization", "Bearer " + token)
                .header(ReauthTestSupport.HEADER, reauth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("role", role))));
    }

    private ResultActions deleteGrant(String token, long vmId, long grantId) throws Exception {
        return mockMvc.perform(delete(accessPath(vmId) + "/" + grantId)
                .header("Authorization", "Bearer " + token)
                .header(ReauthTestSupport.HEADER, reauth(token)));
    }

    private static Map<String, Object> userGrant(long userId, String role) {
        Map<String, Object> body = new HashMap<>();
        body.put("granteeType", "USER");
        body.put("userId", userId);
        body.put("role", role);
        return body;
    }

    private static Map<String, Object> groupGrant(String role) {
        Map<String, Object> body = new HashMap<>();
        body.put("granteeType", "GROUP");
        body.put("role", role);
        return body;
    }

    /** Sudo-mode gate: mint the caller's own {@code X-Reauth-Token}. */
    private String reauth(String token) {
        return ReauthTestSupport.seededReauthFor(jdbcTemplate, jwtService, token);
    }

    // ── fixture helpers ────────────────────────────────────────────────────

    private long createVm() {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, image_id,
                                         req_vcpu, req_memory_mb, req_disk_gb)
                values (?, ?, ?, '접근 권한 테스트', ?, 1, 1024, 10)
                returning id
                """, Long.class, groupId, orgId, vmOwner.getId(), image.getId());
        String hostname = "vmacc-" + UUID.randomUUID().toString().substring(0, 12);
        long vmId = jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?, 'STOPPED'::vm_status)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname,
                image.getId(), VMID_SEQ.incrementAndGet());
        // Nothing approved this VM, so its list starts empty: name its owner the
        // way the approval step would have.
        grantVmToUser(jdbcTemplate, vmId, vmOwner.getId(), "OWNER");
        return vmId;
    }

    /** A brand-new active account already added to this test's owning group. */
    private User groupMember(String emailPrefix, String name) throws Exception {
        User user = ensureUser(emailPrefix + "-" + UUID.randomUUID() + "@pusan.ac.kr", name);
        addMember(groupId, user.getEmail());
        return user;
    }

    private long submitRequest(String token) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("groupId", groupId);
        body.put("orgId", orgId);
        body.put("imageId", image.getId());
        body.put("flavorId", flavor.getId());
        body.put("purpose", "접근 권한 테스트용 신청");
        body.put("reqVcpu", flavor.getVcpu());
        body.put("reqMemoryMb", flavor.getMemoryMb());
        body.put("reqDiskGb", flavor.getDiskGb());
        String response = mockMvc.perform(post("/api/v1/vm-requests")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private ResultActions approveRequest(long requestId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("grantedVcpu", flavor.getVcpu());
        body.put("grantedMemoryMb", flavor.getMemoryMb());
        body.put("grantedDiskGb", flavor.getDiskGb());
        body.put("grantedImageId", image.getId());
        body.put("comment", "신청 사양 그대로 승인합니다.");
        return mockMvc.perform(post("/api/v1/admin/vm-requests/" + requestId + "/approve")
                .header("Authorization", "Bearer " + orgAdminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private long createTeam(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + groupOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("kind", "TEAM",
                                "name", "접근 권한 테스트 " + slug, "slug", slug))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    /** Adds a plain member: the group ladder has only OWNER and MEMBER left. */
    private void addMember(long groupId, String email) throws Exception {
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + groupOwnerToken)
                        .header(ReauthTestSupport.HEADER, reauth(groupOwnerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "role", "MEMBER"))))
                .andExpect(status().isCreated());
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }

    // ── assertion helpers ──────────────────────────────────────────────────

    private int grantCount(long vmId) {
        return jdbcTemplate.queryForObject("""
                select count(*) from resource_access_grants
                 where resource_type = 'VM' and resource_id = ?
                """, Integer.class, vmId);
    }

    /**
     * Grants this person holds on the VMs of one group. Scoped to the group
     * because the fixture accounts are shared across the methods here and carry
     * grants on other groups' VMs, which the cascade correctly leaves alone.
     */
    private int userGrantCountInGroup(long userId, long groupId) {
        return jdbcTemplate.queryForObject("""
                select count(*) from resource_access_grants g
                 where g.grantee_type = 'USER' and g.user_id = ?
                   and g.resource_type = 'VM'
                   and g.resource_id in (select v.id from vms v where v.group_id = ?)
                """, Integer.class, userId, groupId);
    }

    private String roleOfGrant(long grantId) {
        return jdbcTemplate.queryForObject(
                "select role::text from resource_access_grants where id = ?", String.class, grantId);
    }

    private String roleOfUserGrant(long vmId, long userId) {
        return jdbcTemplate.queryForObject("""
                select role::text from resource_access_grants
                 where resource_type = 'VM' and resource_id = ? and user_id = ?
                """, String.class, vmId, userId);
    }

    private long userGrantId(long vmId, long userId) {
        return jdbcTemplate.queryForObject("""
                select id from resource_access_grants
                 where resource_type = 'VM' and resource_id = ? and user_id = ?
                """, Long.class, vmId, userId);
    }

    private int auditCount(String action, long vmId) {
        return jdbcTemplate.queryForObject("""
                select count(*) from audit_logs
                 where action = ? and target_type = 'vm' and target_id = ?
                """, Integer.class, action, vmId);
    }

    /** One key out of the newest audit detail for {@code action} on this VM. */
    private String auditDetail(String action, long vmId, String key) {
        return jdbcTemplate.queryForObject("""
                select detail ->> ? from audit_logs
                 where action = ? and target_type = 'vm' and target_id = ?
                 order by id desc limit 1
                """, String.class, key, action, vmId);
    }

    private String groupAuditDetail(String action, long groupId, String key) {
        return jdbcTemplate.queryForObject("""
                select detail ->> ? from audit_logs
                 where action = ? and target_type = 'group' and target_id = ?
                 order by id desc limit 1
                """, String.class, key, action, groupId);
    }

    private int vmCountForRequest(long requestId) {
        return jdbcTemplate.queryForObject("select count(*) from vms where request_id = ?",
                Integer.class, requestId);
    }
}
