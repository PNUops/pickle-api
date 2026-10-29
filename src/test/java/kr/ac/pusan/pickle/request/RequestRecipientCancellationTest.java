package kr.ac.pusan.pickle.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.ac.pusan.pickle.llm.LlmKeyRequestSupport;
import kr.ac.pusan.pickle.request.dto.CancelRequestRecipientRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.InvitationClaimService;
import kr.ac.pusan.pickle.workspace.dto.TerminationConfirmation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class RequestRecipientCancellationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired kr.ac.pusan.pickle.security.institution.InstitutionActorProvider actorProvider;
    @Autowired UserRepository users;
    @Autowired JwtService tokens;
    @Autowired ObjectMapper json;
    @Autowired RequestRecipientCancellationService cancellations;
    @Autowired RequestRecipientMaterializer materializer;
    @Autowired RequestRecipientService recipientService;
    @Autowired RequestRepository requests;
    @Autowired TransactionTemplate tx;
    @Autowired jakarta.persistence.EntityManager entities;
    @Autowired InvitationClaimService claims;
    @Autowired kr.ac.pusan.pickle.workspace.WorkspaceService workspaceService;
    @MockitoSpyBean LlmKeyRequestSupport keyHandler;
    @MockitoSpyBean RequestRecipientRepository recipientRepository;
    private User owner;
    private long workspace;
    private long org;

    @BeforeEach
    void setup() {
        reset(keyHandler, recipientRepository);
        owner = user(UserRole.USER);
        org = SeedFixtures.seedOrgId(jdbc);
        workspace = jdbc.queryForObject("insert into workspaces(kind,name) values('PROJECT',?) returning id", Long.class, "취소 합성 " + UUID.randomUUID());
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", workspace, owner.getId());
    }

    @Test
    void bothRoutesRequireTheirActualCurrentAuthorityAndCapabilityMatches() throws Exception {
        long request = request("LLM_API_KEY");
        long recipient = recipient(request, "QUEUED", owner, null);
        for (UserRole role : UserRole.values()) {
            User actor = user(role);
            boolean writer = List.of(UserRole.ORG_MANAGER, UserRole.ORG_ADMIN, UserRole.SYS_ADMIN).contains(role);
            mvc.perform(get(path(request, recipient, true) + "/cancellation-impact").header("Authorization", bearer(actor)))
                    .andExpect(writer ? status().isOk() : status().isForbidden());
            mvc.perform(get(path(request, recipient, false) + "/cancellation-impact").header("Authorization", bearer(actor))).andExpect(status().isForbidden());
            jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", workspace, actor.getId());
            assertThat(preview(request, recipient, actor, false).get("allowed").asBoolean()).isTrue();
            JsonNode detail = detail(request, actor, role != UserRole.USER);
            assertThat(detail.at("/recipients/0/cancellationCapability/ownerAllowed").asBoolean()).isTrue();
            assertThat(detail.at("/recipients/0/cancellationCapability/supportAllowed").asBoolean()).isEqualTo(writer);
        }
        User member = user(UserRole.USER);
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'MEMBER')", workspace, member.getId());
        jdbc.update("update requests set requester_id=? where id=?", member.getId(), request);
        jdbc.update("update request_recipients set user_id=? where id=?", member.getId(), recipient);
        mvc.perform(get(path(request, recipient, false) + "/cancellation-impact").header("Authorization", bearer(member))).andExpect(status().isForbidden());
    }

    @Test
    void supportWritersCancelAcrossInstitutionsIncludingSystemTierRecipients() throws Exception {
        long otherOrg = jdbc.queryForObject("insert into orgs(name) values(?) returning id", Long.class, "지원 대상 기관 " + UUID.randomUUID());
        User target = user(UserRole.SYS_ADMIN);
        for (UserRole role : List.of(UserRole.ORG_MANAGER, UserRole.ORG_ADMIN, UserRole.SYS_ADMIN)) {
            User writer = user(role);
            long request = request("LLM_API_KEY");
            jdbc.update("update requests set org_id=? where id=?", otherOrg, request);
            long recipient = recipient(request, "QUEUED", target, null);
            String fingerprint = preview(request, recipient, writer, true).get("fingerprint").asString();
            cancel(request, recipient, writer, true, fingerprint, "현재 지원 쓰기 권한으로 취소", 200);
            assertThat(state(recipient)).isEqualTo("CANCELED");
            assertThat(jdbc.queryForObject("select status::text from requests where id=?", String.class, request)).isEqualTo("APPROVED");
        }
    }

    @Test
    void failedHistoryApprovalAndOtherRelationsSurviveAndReadbackReturnsTheReceipt() throws Exception {
        long request = request("VM");
        long invitation = invitation();
        long selected = recipient(request, "FAILED", null, invitation);
        long other = recipient(request, "QUEUED", owner, null);
        jdbc.update("update request_recipients set reason=?,attempts=7 where id=?", "exact previous failure\n두 번째 줄", selected);
        String review = jdbc.queryForObject("select row_to_json(r)::text from request_reviews r where request_id=?", String.class, request);
        String beforeInvite = jdbc.queryForObject("select row_to_json(i)::text from workspace_invitations i where id=?", String.class, invitation);
        JsonNode impact = preview(request, selected, owner, false);
        JsonNode result = cancel(request, selected, owner, false, impact.get("fingerprint").asString(), "재시도를 중단합니다", 200);
        assertThat(result.get("previousReason").asString()).isEqualTo("exact previous failure\n두 번째 줄");
        assertThat(result.get("previousAttempts").asInt()).isEqualTo(7);
        assertThat(state(selected)).isEqualTo("CANCELED");
        assertThat(state(other)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select reason from request_recipients where id=?", String.class, selected)).isEqualTo("exact previous failure\n두 번째 줄");
        assertThat(jdbc.queryForObject("select row_to_json(r)::text from request_reviews r where request_id=?", String.class, request)).isEqualTo(review);
        assertThat(jdbc.queryForObject("select row_to_json(i)::text from workspace_invitations i where id=?", String.class, invitation)).isEqualTo(beforeInvite);
        JsonNode readback = detail(request, owner, false).at("/recipients/0/cancellation");
        assertThat(readback).isEqualTo(result);
        for (String field : List.of("actor_id", "ip", "confirmationFingerprint")) assertThat(readback.has(field)).isFalse();
        assertThat(jdbc.queryForObject("select target_org_id from audit_logs where action='request.recipient_cancel' and target_id=?", Long.class, pub("request_recipients", selected).toString())).isEqualTo(org);
    }

    @Test
    void replayIsIdempotentButDifferentReasonActorBindingAndAutomaticCancellationAreNot() throws Exception {
        long request = request("LLM_API_KEY");
        long recipient = recipient(request, "PENDING_JOIN", null, invitation());
        String fingerprint = preview(request, recipient, owner, false).get("fingerprint").asString();
        JsonNode first = cancel(request, recipient, owner, false, fingerprint, "중복 응답 확인", 200);
        assertThat(cancel(request, recipient, owner, false, fingerprint, "중복 응답 확인", 200)).isEqualTo(first);
        cancel(request, recipient, owner, false, fingerprint, "다른 사유", 409);
        cancel(request, recipient, owner, false, "changed", "중복 응답 확인", 409);
        User other = user(UserRole.USER);
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", workspace, other.getId());
        cancel(request, recipient, other, false, fingerprint, "중복 응답 확인", 409);
        assertThat(audits(recipient)).isEqualTo(1);
        long automatic = recipient(request, "CANCELED", null, invitation());
        cancel(request, automatic, owner, false, preview(request, automatic, owner, false).get("fingerprint").asString(), "이미 자동 종료", 409);
        assertThat(audits(automatic)).isZero();
        jdbc.update("update workspace_members set role='MEMBER' where workspace_id=? and user_id=?", workspace, owner.getId());
        cancel(request, recipient, owner, false, fingerprint, "중복 응답 확인", 403);
    }

    @Test
    void currentDatabaseRoleIsRequiredEvenWhenThePassedPrincipalStillHasSupportAuthority() throws Exception {
        User writer = user(UserRole.ORG_MANAGER);
        AuthenticatedUser captured = actor(writer);
        long request = request("VM"); long recipient = recipient(request, "QUEUED", owner, null);
        String fingerprint = cancellations.preview(captured, pub("requests", request), pub("request_recipients", recipient), true).fingerprint();
        jdbc.update("delete from user_org_roles where user_id=?", writer.getId());
        jdbc.update("update users set role='USER' where id=?", writer.getId());
        assertThatThrownBy(() -> cancellations.cancel(captured, pub("requests", request), pub("request_recipients", recipient),
                new CancelRequestRecipientRequest("회수된 권한", new TerminationConfirmation(fingerprint, true)), true, "127.0.0.1"))
                .hasMessageContaining("접근 권한");
        assertThat(state(recipient)).isEqualTo("QUEUED");
    }

    @Test
    void pointerAndEveryRegisteredResourceIncludingEndedAndUnissuedRowsBlockOnlyWhenAttributionIsUnclear() throws Exception {
        for (String type : List.of("VM", "LLM_API_KEY")) {
            long request = request(type);
            long selected = recipient(request, "FAILED", owner, null);
            jdbc.update("update request_recipients set resource_id=987654321 where id=?", selected);
            assertThat(preview(request, selected, owner, false).get("allowed").asBoolean()).isFalse();
            jdbc.update("update request_recipients set resource_id=null where id=?", selected);
            long resource = resource(type, request);
            assertThat(preview(request, selected, owner, false).get("allowed").asBoolean()).isFalse();
            jdbc.update("update " + ("VM".equals(type) ? "vms" : "llm_api_keys") + " set status=?::" + ("VM".equals(type) ? "vm_status" : "llm_api_key_status") + " where id=?",
                    "VM".equals(type) ? "DELETED" : "REVOKED", resource);
            assertThat(preview(request, selected, owner, false).get("allowed").asBoolean()).isFalse();
            long other = recipient(request, "CREATED", null, invitation());
            jdbc.update("update request_recipients set resource_id=? where id=?", resource, other);
            jdbc.update("update request_recipients set status='FAILED' where id=?", other);
            assertThat(preview(request, selected, owner, false).get("allowed").asBoolean()).isFalse();
            jdbc.update("update request_recipients set status='CREATED' where id=?", other);
            long duplicate = recipient(request, "CREATED", null, invitation());
            jdbc.update("update request_recipients set resource_id=? where id=?", resource, duplicate);
            assertThat(preview(request, selected, owner, false).get("allowed").asBoolean()).isFalse();
            jdbc.update("update request_recipients set resource_id=null,status='SKIPPED_INELIGIBLE' where id=?", duplicate);
            JsonNode impact = preview(request, selected, owner, false);
            assertThat(impact.get("allowed").asBoolean()).isTrue();
            cancel(request, selected, owner, false, impact.get("fingerprint").asString(), "다른 대상자 결과 보존", 200);
            assertThat(state(other)).isEqualTo("CREATED");
        }
    }

    @Test
    void missingFalseAndBlankAcknowledgementsNeverCancelOrWriteAudit() throws Exception {
        long request = request("VM"); long recipient = recipient(request, "QUEUED", owner, null);
        String fingerprint = preview(request, recipient, owner, false).get("fingerprint").asString();
        for (Map<String, Object> body : List.of(
                Map.<String, Object>of("reason", "확인 누락"),
                Map.<String, Object>of("reason", "명시 확인 없음", "confirmation", Map.of("fingerprint", fingerprint, "confirmed", false)),
                Map.<String, Object>of("reason", "빈 확인값", "confirmation", Map.of("fingerprint", " ", "confirmed", true)),
                Map.<String, Object>of("reason", " ", "confirmation", Map.of("fingerprint", fingerprint, "confirmed", true)))) {
            mvc.perform(post(path(request, recipient, false) + "/cancel").header("Authorization", bearer(owner))
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                    .andExpect(status().isUnprocessableContent());
        }
        assertThat(state(recipient)).isEqualTo("QUEUED"); assertThat(audits(recipient)).isZero();
    }

    @Test
    void confirmationBindsExactStateAndRegistrationButNotObservationTime() throws Exception {
        long request = request("VM"); long recipient = recipient(request, "QUEUED", owner, null);
        JsonNode first = preview(request, recipient, owner, false);
        assertThat(preview(request, recipient, owner, false).get("fingerprint")).isEqualTo(first.get("fingerprint"));
        jdbc.update("update request_recipients set status='FAILED',reason='new failure',attempts=1 where id=?", recipient);
        cancel(request, recipient, owner, false, first.get("fingerprint").asString(), "변경된 상태", 409);
        JsonNode next = preview(request, recipient, owner, false);
        resource("VM", request);
        cancel(request, recipient, owner, false, next.get("fingerprint").asString(), "등록 경합", 409);
        assertThat(state(recipient)).isEqualTo("FAILED");
    }

    @Test
    void auditInsertFailureRollsBackRecipientAndLeavesNoReceipt() throws Exception {
        long request = request("VM"); long recipient = recipient(request, "FAILED", owner, null);
        String fingerprint = preview(request, recipient, owner, false).get("fingerprint").asString();
        jdbc.execute("create function reject_recipient_cancel_audit() returns trigger language plpgsql as $$ begin if new.action='request.recipient_cancel' then raise exception 'synthetic audit failure'; end if; return new; end $$");
        jdbc.execute("create trigger reject_recipient_cancel_audit before insert on audit_logs for each row execute function reject_recipient_cancel_audit()");
        try { cancel(request, recipient, owner, false, fingerprint, "감사 실패", 500); }
        finally {
            jdbc.execute("drop trigger reject_recipient_cancel_audit on audit_logs");
            jdbc.execute("drop function reject_recipient_cancel_audit()");
        }
        assertThat(state(recipient)).isEqualTo("FAILED");
        assertThat(audits(recipient)).isZero();
        assertThat(detail(request, owner, false).at("/recipients/0/cancellation").isNull()).isTrue();
    }

    @Test
    void cancellationWinsBeforeMaterializationAndClaimCannotReviveIt() throws Exception {
        long request = request("LLM_API_KEY"); long pendingInvitation = invitation();
        long recipient = recipient(request, "PENDING_JOIN", null, pendingInvitation);
        JsonNode impact = preview(request, recipient, owner, false);
        cancel(request, recipient, owner, false, impact.get("fingerprint").asString(), "가입 대기 중단", 200);
        String email = jdbc.queryForObject("select invitee_email from workspace_invitations where id=?", String.class, pendingInvitation);
        User joined = user(UserRole.USER);
        jdbc.update("update users set email=? where id=?", email, joined.getId());
        claims.claimByEmail(users.findById(joined.getId()).orElseThrow());
        assertThat(materializer.processOne(recipient)).isEqualTo(RequestRecipientMaterializer.Outcome.NOT_APPLICABLE);
        assertThat(state(recipient)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("select count(*) from llm_api_keys where request_id=?", Long.class, request)).isZero();
    }

    @Test
    void actualMaterializerWinsAndTheWaitingCancellationMustReconfirm() throws Exception {
        long request = request("LLM_API_KEY"); long recipient = recipient(request, "QUEUED", owner, null);
        String fingerprint = preview(request, recipient, owner, false).get("fingerprint").asString();
        CountDownLatch creating = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> { creating.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); return call.callRealMethod(); })
                .when(keyHandler).createFor(any(), any(), anyLong(), any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var create = pool.submit(() -> materializer.processOne(recipient));
            assertThat(creating.await(10, TimeUnit.SECONDS)).isTrue();
            var cancel = pool.submit(() -> cancel(request, recipient, owner, false, fingerprint, "생성과 경합", 409));
            Thread.sleep(150);
            assertThat(cancel.isDone()).isFalse();
            release.countDown();
            assertThat(create.get(15, TimeUnit.SECONDS)).isEqualTo(RequestRecipientMaterializer.Outcome.CREATED);
            cancel.get(15, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
        assertThat(state(recipient)).isEqualTo("CREATED"); assertThat(audits(recipient)).isZero();
    }

    @Test
    void simultaneousDuplicateCancellationWritesOneReceiptAndRetryCannotReviveIt() throws Exception {
        long request = request("VM"); long recipient = recipient(request, "FAILED", owner, null);
        String fingerprint = preview(request, recipient, owner, false).get("fingerprint").asString();
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { start.await(); return cancel(request, recipient, owner, false, fingerprint, "동시 중복", 200); });
            var second = pool.submit(() -> { start.await(); return cancel(request, recipient, owner, false, fingerprint, "동시 중복", 200); });
            start.countDown(); assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertThat(audits(recipient)).isEqualTo(1);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> recipientService.retry(requests.findById(request).orElseThrow(), pub("request_recipients", recipient))))
                .hasMessageContaining("다시 시도");
        assertThat(state(recipient)).isEqualTo("CANCELED");
    }

    @Test
    void failureRecorderWaitingBehindCancellationDoesNotRestoreFailedState() throws Exception {
        long request = request("LLM_API_KEY"); long recipient = recipient(request, "QUEUED", owner, null);
        String fingerprint = preview(request, recipient, owner, false).get("fingerprint").asString();
        CountDownLatch recording = new CountDownLatch(1); CountDownLatch releaseRecording = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 2) {
                recording.countDown(); assertThat(releaseRecording.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return entities.createQuery("select r from RequestRecipient r where r.id=:id", RequestRecipient.class)
                    .setParameter("id", recipient).setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst();
        }).when(recipientRepository).findWithLockById(recipient);
        doAnswer(call -> { throw new IllegalStateException("synthetic creation failure"); })
                .when(keyHandler).createFor(any(), any(), anyLong(), any());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var create = pool.submit(() -> materializer.processOne(recipient));
            assertThat(recording.await(10, TimeUnit.SECONDS)).isTrue();
            cancel(request, recipient, owner, false, fingerprint, "실패 기록보다 먼저 취소", 200);
            releaseRecording.countDown(); create.get(15, TimeUnit.SECONDS);
        } finally { releaseRecording.countDown(); pool.shutdownNow(); }
        assertThat(state(recipient)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("select attempts from request_recipients where id=?", Integer.class, recipient)).isZero();
    }

    @Test
    void aRetryThatReadFailedBeforeCancellationMustRefreshAfterItsRowLock() throws Exception {
        long request = request("VM"); long recipient = recipient(request, "FAILED", owner, null);
        String fingerprint = preview(request, recipient, owner, false).get("fingerprint").asString();
        CountDownLatch retryRead = new CountDownLatch(1); CountDownLatch releaseRetry = new CountDownLatch(1);
        doAnswer(call -> {
            var found = entities.createQuery("select r from RequestRecipient r where r.publicId=:publicId and r.requestId=:requestId", RequestRecipient.class)
                    .setParameter("publicId", call.getArgument(0)).setParameter("requestId", call.getArgument(1)).getResultStream().findFirst();
            if ("synthetic-recipient-retry".equals(Thread.currentThread().getName())) {
                retryRead.countDown(); assertThat(releaseRetry.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return found;
        }).when(recipientRepository).findByPublicIdAndRequestId(pub("request_recipients", recipient), request);
        var pool = Executors.newSingleThreadExecutor(task -> new Thread(task, "synthetic-recipient-retry"));
        try {
            var retry = pool.submit(() -> assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                    recipientService.retry(requests.findById(request).orElseThrow(), pub("request_recipients", recipient))))
                    .hasMessageContaining("다시 시도"));
            assertThat(retryRead.await(10, TimeUnit.SECONDS)).isTrue();
            cancel(request, recipient, owner, false, fingerprint, "재시도와 경합", 200);
            releaseRetry.countDown(); retry.get(15, TimeUnit.SECONDS);
        } finally { releaseRetry.countDown(); pool.shutdownNow(); }
        assertThat(state(recipient)).isEqualTo("CANCELED");
    }

    @Test
    void actualMaterializerWaitsForCancellationCommitAndDoesNotReviveTheRecipient() throws Exception {
        long request = request("LLM_API_KEY"); long recipient = recipient(request, "QUEUED", owner, null);
        String fingerprint = preview(request, recipient, owner, false).get("fingerprint").asString();
        CountDownLatch canceled = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var cancel = pool.submit(() -> tx.executeWithoutResult(status -> {
                cancellations.cancel(actor(owner), pub("requests", request), pub("request_recipients", recipient),
                        new CancelRequestRecipientRequest("생성보다 먼저 취소", new TerminationConfirmation(fingerprint, true)), false, "127.0.0.1");
                canceled.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }));
            assertThat(canceled.await(10, TimeUnit.SECONDS)).isTrue();
            var create = pool.submit(() -> materializer.processOne(recipient));
            Thread.sleep(150); assertThat(create.isDone()).isFalse();
            release.countDown(); cancel.get(15, TimeUnit.SECONDS);
            assertThat(create.get(15, TimeUnit.SECONDS)).isEqualTo(RequestRecipientMaterializer.Outcome.NOT_APPLICABLE);
        } finally { release.countDown(); pool.shutdownNow(); }
        assertThat(state(recipient)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("select count(*) from llm_api_keys where request_id=?", Long.class, request)).isZero();
    }

    @Test
    void anOwnerChangeCommittedWhileCancellationWaitsMustDenyTheFormerOwner() throws Exception {
        long request = request("VM"); long recipient = recipient(request, "QUEUED", owner, null);
        String fingerprint = preview(request, recipient, owner, false).get("fingerprint").asString();
        User successor = user(UserRole.USER);
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", workspace, successor.getId());
        User supportWriter = user(UserRole.SYS_ADMIN);
        var pool = Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> response = new java.util.concurrent.atomic.AtomicReference<>();
            tx.executeWithoutResult(transaction -> {
                jdbc.queryForObject("select pg_advisory_xact_lock(1129074511,1)::text", String.class);
                response.set(pool.submit(() -> cancel(request, recipient, owner, false, fingerprint, "OWNER 변경과 경합", 403)));
                awaitAdministrationWait();
                workspaceService.updateMemberRole(actor(supportWriter), pub("workspaces", workspace), owner.getPublicId(),
                        new kr.ac.pusan.pickle.workspace.dto.UpdateWorkspaceMemberRequest(kr.ac.pusan.pickle.workspace.WorkspaceMemberRole.MEMBER), "127.0.0.1", true);
            });
            response.get().get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(state(recipient)).isEqualTo("QUEUED"); assertThat(audits(recipient)).isZero();
        assertThat(jdbc.queryForObject("select role::text from workspace_members where workspace_id=? and user_id=?", String.class, workspace, owner.getId())).isEqualTo("MEMBER");
    }

    @Test
    void anInvitationClaimCommittedWhileCancellationWaitsMustInvalidateTheExactRecipientBinding() throws Exception {
        long request = request("LLM_API_KEY"); long invitation = invitation();
        long recipient = recipient(request, "PENDING_JOIN", null, invitation);
        String fingerprint = preview(request, recipient, owner, false).get("fingerprint").asString();
        User joined = user(UserRole.USER);
        jdbc.update("update users set email=(select invitee_email from workspace_invitations where id=?) where id=?", invitation, joined.getId());
        User claimant = users.findById(joined.getId()).orElseThrow();
        var pool = Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> response = new java.util.concurrent.atomic.AtomicReference<>();
            tx.executeWithoutResult(transaction -> {
                jdbc.queryForObject("select pg_advisory_xact_lock(1129074511,1)::text", String.class);
                response.set(pool.submit(() -> cancel(request, recipient, owner, false, fingerprint, "가입 확정과 경합", 409)));
                awaitAdministrationWait();
                claims.claimByEmail(claimant);
            });
            response.get().get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(state(recipient)).isEqualTo("QUEUED"); assertThat(audits(recipient)).isZero();
        assertThat(jdbc.queryForObject("select user_id from request_recipients where id=?", Long.class, recipient)).isEqualTo(joined.getId());
        assertThat(jdbc.queryForObject("select status from workspace_invitations where id=?", String.class, invitation)).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("select count(*) from llm_api_keys where request_id=?", Long.class, request)).isZero();
    }

    private void awaitAdministrationWait() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject("select count(*) from pg_locks where locktype='advisory' and classid=1129074511 and objid=1 and not granted", Long.class) == 0) {
            if (System.nanoTime() > deadline) throw new AssertionError("Cancellation did not reach the administration lock");
            try { Thread.sleep(20); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        }
    }

    private long request(String type) {
        long request = "VM".equals(type) ? RequestFixtures.insertVmRequest(jdbc, workspace, org, owner.getId(), "합성 신청", null)
                : RequestFixtures.insertLlmKeyRequest(jdbc, workspace, org, owner.getId(), "합성 신청");
        if ("VM".equals(type)) RequestFixtures.approveVmRequest(jdbc, request, owner.getId(), null, 1, 1024, 10);
        else {
            jdbc.update("update llm_key_request_details set granted_passthrough_endpoints='[\"chat\"]' where request_id=?", request);
            jdbc.update("insert into request_reviews(request_id,reviewer_id,decision) values(?,?,'APPROVE')", request, owner.getId());
        }
        jdbc.update("update requests set status='APPROVED' where id=?", request);
        return request;
    }
    private long recipient(long request, String status, User user, Long invitation) {
        return jdbc.queryForObject("insert into request_recipients(public_id,request_id,user_id,invitation_id,status) values(?,?,?,?,?) returning id", Long.class,
                UUID.randomUUID(), request, user == null ? null : user.getId(), invitation, status);
    }
    private long invitation() {
        return jdbc.queryForObject("insert into workspace_invitations(workspace_id,invitee_email,role,invited_by) values(?,?,'MEMBER',?) returning id", Long.class,
                workspace, UUID.randomUUID() + "@pusan.ac.kr", owner.getId());
    }
    private long resource(String type, long request) {
        if ("LLM_API_KEY".equals(type)) return jdbc.queryForObject("insert into llm_api_keys(workspace_id,org_id,request_id,name,purpose,created_by) values(?,?,?,'합성 키','합성',?) returning id", Long.class, workspace, org, request, owner.getId());
        return jdbc.queryForObject("insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status) values((select min(id) from nodes),?,?,?,'합성 VM',?,(select min(id) from os_images),1,1024,10,'CREATING') returning id", Long.class,
                workspace, org, request, "cancel-" + UUID.randomUUID());
    }
    private User user(UserRole role) {
        User user = new User(UUID.randomUUID() + "@pusan.ac.kr", "unused", "합성 사용자"); user.setStatus(UserStatus.ACTIVE); user.setRole(role);
        user = users.saveAndFlush(user);
        if (role.isOrgTier()) SeedFixtures.grantOrgRole(jdbc, user.getId(), SeedFixtures.seedOrgId(jdbc), role);
        return user;
    }
    private AuthenticatedUser actor(User user) { return actorProvider.authenticate(user.getPublicId(), user.getTokenVersion()).orElseThrow(); }
    private UUID pub(String table, long id) { return SeedFixtures.publicId(jdbc, table, id); }
    private String bearer(User user) { return "Bearer " + tokens.createAccessToken(user); }
    private String state(long recipient) { return jdbc.queryForObject("select status from request_recipients where id=?", String.class, recipient); }
    private long audits(long recipient) { return jdbc.queryForObject("select count(*) from audit_logs where action='request.recipient_cancel' and target_id=?", Long.class, pub("request_recipients", recipient).toString()); }
    private String path(long request, long recipient, boolean support) { return "/api/v1/" + (support ? "admin/" : "") + "requests/" + pub("requests", request) + "/recipients/" + pub("request_recipients", recipient); }
    private JsonNode preview(long request, long recipient, User actor, boolean support) throws Exception {
        return json.readTree(mvc.perform(get(path(request, recipient, support) + "/cancellation-impact").header("Authorization", bearer(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode detail(long request, User actor, boolean support) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/" + (support ? "admin/" : "") + "requests/" + pub("requests", request)).header("Authorization", bearer(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode cancel(long request, long recipient, User actor, boolean support, String fingerprint, String reason, int expected) throws Exception {
        String body = mvc.perform(post(path(request, recipient, support) + "/cancel").header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("reason", reason,
                        "confirmation", Map.of("fingerprint", fingerprint, "confirmed", true)))))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }
}
