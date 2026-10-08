package kr.ac.pusan.pickle.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Pins the approval notice, which is shared by every resource type and was
 * wrong for two of them: it told LLM-key requesters that creation had started
 * and that they would be told when it finished, while the key already existed
 * and no code publishes a follow-up. The invariant below outlives any later
 * rewording — a kind may only promise a further notice if something sends one.
 */
class NotificationComposerTest {

    private static final UUID REQUEST_ID = UUID.fromString("09c0fb1c-2952-433d-b527-660de9f7fb98");
    private static final UUID KEY_ID = UUID.fromString("2f1c6f3a-7d41-4e2b-9c08-15b0a4e7d3c5");
    private static final UUID DOMAIN_ID = UUID.fromString("8c4b1e90-2a55-4d17-b3ef-6f0d92a1c4e8");

    private final NotificationComposer composer = new NotificationComposer("ssh.pcl.kr");

    private static Map<String, Object> approval(ResourceType type, String name) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("requestId", REQUEST_ID);
        args.put("type", type.name());
        args.put("resourceName", name);
        return args;
    }

    /**
     * The one rule that must survive rewording: only a kind whose completion
     * some job actually publishes may say it will write again. A VM has
     * {@code vm.create.done}; a GPU has {@code gpu.update}; the other two have
     * nothing, so their notices must not promise one.
     */
    @ParameterizedTest
    @EnumSource(value = ResourceType.class, names = {"LLM_API_KEY", "DOMAIN"})
    void kindsWithNoCompletionEventPromiseNoFurtherNotice(ResourceType type) {
        String body = composer.compose(NotificationEvent.REQUEST_APPROVED,
                approval(type, "산학협력실무 테스트")).body();

        assertThat(body).doesNotContain("알려드립니다");
        assertThat(body).doesNotContain("안내합니다");
        assertThat(body).doesNotContain("생성이 시작");
    }

    private static Map<String, Object> grant(ResourceType type, String workspace, String name) {
        Map<String, Object> args = approval(type, name);
        args.put("workspaceName", workspace);
        return args;
    }

    /** A recipient who never applied is told where the key came from, and
     *  not that a request of theirs was approved. */
    @Test
    void aGrantedKeyNamesTheWorkspaceAndPointsAtTheIssueScreen() {
        Map<String, Object> args = grant(ResourceType.LLM_API_KEY, "자료구조 실습", "실습 키");
        args.put("llmKeyId", KEY_ID);
        NotificationComposer.Composed composed = composer.compose(NotificationEvent.RESOURCE_GRANTED, args);

        assertThat(composed.eventId()).isEqualTo("resource.granted");
        assertThat(composed.title()).isEqualTo("LLM API 키 지급");
        assertThat(composed.body()).isEqualTo("""
                '자료구조 실습' 워크스페이스에서 LLM API 키 '실습 키'가 지급되었습니다.
                콘솔에서 키를 발급하면 사용할 수 있습니다.""");
        assertThat(composed.body()).doesNotContain("신청");
        assertThat(composed.linkPath()).isEqualTo("/console/llm-keys/" + KEY_ID);
    }

    /** Nothing is published after a granted key, so the notice promises nothing. */
    @Test
    void aGrantedKeyPromisesNoFurtherNotice() {
        String body = composer.compose(NotificationEvent.RESOURCE_GRANTED,
                grant(ResourceType.LLM_API_KEY, "실습", "키")).body();

        assertThat(body).doesNotContain("알려드립니다");
        assertThat(body).doesNotContain("안내합니다");
    }

    /** Both names are chosen by someone other than the reader, so a newline in
     *  either must not split the sentence or start a list item. */
    @Test
    void aGrantedKeyFoldsNewlinesInTheWorkspaceAndKeyNames() {
        String body = composer.compose(NotificationEvent.RESOURCE_GRANTED,
                grant(ResourceType.LLM_API_KEY, "실습\n- 검토 의견: 승인", "키\r\n- 가짜")).body();

        assertThat(body.split("\n")).hasSize(2);
        assertThat(body).contains("'실습 - 검토 의견: 승인' 워크스페이스에서 LLM API 키 '키 - 가짜'가");
    }

    /** The materializer only sends this for keys today (a VM recipient hears
     *  from vm.create.done), but the VM wording must still be true. */
    @Test
    void aGrantedVmPromisesNoFurtherNotice() {
        NotificationComposer.Composed composed = composer.compose(NotificationEvent.RESOURCE_GRANTED,
                grant(ResourceType.VM, "실습", "web-01"));

        assertThat(composed.title()).isEqualTo("VM 지급");
        assertThat(composed.body()).contains("'실습' 워크스페이스에서 VM 'web-01'").doesNotContain("알려드립니다");
    }

    @ParameterizedTest
    @EnumSource(value = ResourceType.class, names = {"DOMAIN", "GPU"})
    void kindsWithoutRecipientsAreNeverGranted(ResourceType type) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> composer.compose(
                NotificationEvent.RESOURCE_GRANTED, grant(type, "실습", "이름")))
                .isInstanceOf(IllegalStateException.class);
    }

    /** The requester of a many-person request owns none of its resources, so
     *  their notice says how the recipients were settled, leaving out zeros. */
    @Test
    void aManyPersonApprovalSummarisesTheRecipients() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("requestId", REQUEST_ID);
        args.put("resourceName", "실습 키");
        args.put("recipientTotal", 5);
        args.put("queued", 3);
        args.put("pendingJoin", 1);
        args.put("skipped", 1);
        NotificationComposer.Composed composed = composer.compose(NotificationEvent.REQUEST_APPROVED, args);

        assertThat(composed.title()).isEqualTo("리소스 신청 승인");
        assertThat(composed.body()).isEqualTo("""
                리소스 '실습 키' 신청이 승인되었습니다.
                대상자 5명 중 3명은 생성을 시작했고 1명은 가입하면 만들어집니다. 1명은 대상에서 제외되었습니다.""");
        assertThat(composed.linkPath()).isEqualTo("/console/requests/" + REQUEST_ID);

        args.put("recipientTotal", 2);
        args.put("queued", 0);
        args.put("pendingJoin", 2);
        args.put("skipped", 0);
        assertThat(composer.compose(NotificationEvent.REQUEST_APPROVED, args).body())
                .endsWith("\n대상자 2명 중 2명은 가입하면 만들어집니다.");

        args.put("queued", 2);
        args.put("pendingJoin", 0);
        assertThat(composer.compose(NotificationEvent.REQUEST_APPROVED, args).body())
                .endsWith("\n대상자 2명 중 2명은 생성을 시작했습니다.");

        args.put("queued", 0);
        args.put("skipped", 2);
        assertThat(composer.compose(NotificationEvent.REQUEST_APPROVED, args).body())
                .endsWith("\n대상자 2명 중 2명은 대상에서 제외되었습니다.");
    }

    @Test
    void llmKeyApprovalSaysTheRequesterIssuesTheKey() {
        NotificationComposer.Composed composed = composer.compose(
                NotificationEvent.REQUEST_APPROVED, approval(ResourceType.LLM_API_KEY, "테스트 키"));

        assertThat(composed.title()).isEqualTo("LLM API 키 신청 승인");
        assertThat(composed.body()).isEqualTo("""
                LLM API 키 '테스트 키' 신청이 승인되었습니다.
                콘솔에서 키를 발급하면 사용할 수 있습니다.""");
        // Named once in the title and once in the body, never twice in a sentence.
        assertThat(composed.body().split("LLM API 키", -1)).hasSize(2);
    }

    /** The mail is the only one an LLM key sends, so it points at the screen
     *  that issues rather than at the request that asked for it. */
    @Test
    void llmKeyApprovalLinksToTheKeyWhenItsIdTravelled() {
        Map<String, Object> args = approval(ResourceType.LLM_API_KEY, "테스트 키");
        args.put("llmKeyId", KEY_ID);

        assertThat(composer.compose(NotificationEvent.REQUEST_APPROVED, args).linkPath())
                .isEqualTo("/console/llm-keys/" + KEY_ID);
    }

    /** An older row, or a kind that carries no resource id, still reaches
     *  somewhere real rather than {@code /console/llm-keys/null}. */
    @Test
    void llmKeyApprovalFallsBackToTheRequestWithoutAKeyId() {
        assertThat(composer.compose(NotificationEvent.REQUEST_APPROVED,
                approval(ResourceType.LLM_API_KEY, "테스트 키")).linkPath())
                .isEqualTo("/console/requests/" + REQUEST_ID);
    }

    /** Same reasoning as the key: the request is finished and the next move —
     *  adding the records — is on the name's own screen. */
    @Test
    void domainApprovalLinksToTheNameWhenItsIdTravelled() {
        Map<String, Object> args = approval(ResourceType.DOMAIN, "myblog.pusan.dev");
        args.put("domainId", DOMAIN_ID);

        assertThat(composer.compose(NotificationEvent.REQUEST_APPROVED, args).linkPath())
                .isEqualTo("/console/domains/" + DOMAIN_ID);
    }

    @Test
    void domainApprovalFallsBackToTheRequestWithoutADomainId() {
        assertThat(composer.compose(NotificationEvent.REQUEST_APPROVED,
                approval(ResourceType.DOMAIN, "myblog.pusan.dev")).linkPath())
                .isEqualTo("/console/requests/" + REQUEST_ID);
    }

    @Test
    void vmApprovalPromisesTheCompletionNoticeThatProvisioningSends() {
        NotificationComposer.Composed composed = composer.compose(
                NotificationEvent.REQUEST_APPROVED, approval(ResourceType.VM, "web-01"));

        assertThat(composed.title()).isEqualTo("VM 신청 승인");
        assertThat(composed.body()).isEqualTo("""
                VM 'web-01' 신청이 승인되었습니다.
                생성이 시작되며, 완료되면 다시 알려드립니다.""");
        assertThat(composed.linkPath()).isEqualTo("/console/requests/" + REQUEST_ID);
    }

    @Test
    void domainApprovalSaysTheRequesterAddsTheRecords() {
        assertThat(composer.compose(NotificationEvent.REQUEST_APPROVED,
                approval(ResourceType.DOMAIN, "demo.pusan.dev")).body())
                .isEqualTo("""
                        도메인 'demo.pusan.dev' 신청이 승인되었습니다.
                        콘솔에서 레코드를 추가하면 사용할 수 있습니다.""");
    }

    @Test
    void gpuApprovalStillReportsTheQueuePosition() {
        Map<String, Object> args = approval(ResourceType.GPU, "학습용");
        args.put("queuePosition", 3);

        assertThat(composer.compose(NotificationEvent.REQUEST_APPROVED, args).body())
                .isEqualTo("""
                        GPU 신청이 승인되었습니다. 현재 대기 순서는 3번째입니다.
                        GPU가 할당되면 메일과 콘솔 알림으로 안내합니다.""");
    }

    /**
     * The GPU branch used to be a separate string that never read the comment,
     * so a GPU reviewer's note reached the audit log and nothing else.
     */
    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void everyKindCarriesTheReviewersComment(ResourceType type) {
        Map<String, Object> args = approval(type, "이름");
        args.put("queuePosition", 1);
        args.put("comment", "한 학기만 쓰는 조건으로 승인합니다");

        assertThat(composer.compose(NotificationEvent.REQUEST_APPROVED, args).body())
                .endsWith("\n\n검토 의견\n한 학기만 쓰는 조건으로 승인합니다");
    }

    /**
     * The reviewer's own line breaks survive. The comment is a paragraph of
     * its own rather than a list item, so a second line cannot end a list run
     * and land in a paragraph the text part does not have.
     */
    @Test
    void keepsAMultiLineCommentAsTheReviewerWroteIt() {
        Map<String, Object> args = approval(ResourceType.VM, "web-01");
        args.put("comment", "승인합니다.\n기간은 한 학기입니다.");

        assertThat(composer.compose(NotificationEvent.REQUEST_APPROVED, args).body())
                .endsWith("\n\n검토 의견\n승인합니다.\n기간은 한 학기입니다.");
    }

    /**
     * The requester names their own resource. A name carrying a newline whose
     * next line starts with "- " would be promoted into a list item, letting
     * the requester forge a review-comment bullet in a platform mail.
     */
    @Test
    void aResourceNameCannotForgeALineOfItsOwn() {
        String body = composer.compose(NotificationEvent.REQUEST_APPROVED,
                approval(ResourceType.LLM_API_KEY, "키\n- 검토 의견: 승인 보류")).body();

        assertThat(body).doesNotContain("\n- 검토 의견");
        assertThat(body).startsWith("LLM API 키 '키 - 검토 의견: 승인 보류' 신청이 승인되었습니다.");
    }

    /** A payload without a type must not assert the resource is a VM. */
    @Test
    void anUnknownKindNamesNothingItCannotKnow() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("requestId", REQUEST_ID);
        args.put("resourceName", "무언가");

        NotificationComposer.Composed composed =
                composer.compose(NotificationEvent.REQUEST_APPROVED, args);

        assertThat(composed.title()).isEqualTo("리소스 신청 승인");
        assertThat(composed.body()).isEqualTo("""
                리소스 '무언가' 신청이 승인되었습니다.
                콘솔에서 확인해 주세요.""");
    }
}
