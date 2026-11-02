package kr.ac.pusan.pickle.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import kr.ac.pusan.pickle.llm.alert.UsageAlertAxis;
import kr.ac.pusan.pickle.llm.alert.UsageAlertFacts;
import kr.ac.pusan.pickle.llm.alert.UsageAlertPeriod;
import kr.ac.pusan.pickle.mail.MailHtmlLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConciseNotificationComposerTest {
    private static final UUID ID = UUID.fromString("76151882-c639-490c-8176-ed87bc9e7406");
    private static final Instant NOW = Instant.parse("2026-11-03T03:00:00Z");
    private final NotificationComposer composer = new NotificationComposer("ssh.example.com");

    static Stream<Arguments> usageMessages() {
        return Stream.of(
                Arguments.of(UsageAlertAxis.TOKEN, UsageAlertPeriod.DAILY, 80,
                        "공유 실습 키의 일일 토큰 한도를 80% 이상 사용했어요."),
                Arguments.of(UsageAlertAxis.TOKEN, UsageAlertPeriod.DAILY, 100,
                        "공유 실습 키의 일일 토큰 한도를 모두 사용했어요."),
                Arguments.of(UsageAlertAxis.CREDIT, UsageAlertPeriod.DAILY, 80,
                        "공유 실습 키의 일일 금액 한도를 80% 이상 사용했어요."),
                Arguments.of(UsageAlertAxis.CREDIT, UsageAlertPeriod.DAILY, 100,
                        "공유 실습 키의 일일 금액 한도를 모두 사용했어요."),
                Arguments.of(UsageAlertAxis.CREDIT, UsageAlertPeriod.WEEKLY, 80,
                        "공유 실습 키의 주간 금액 한도를 80% 이상 사용했어요."),
                Arguments.of(UsageAlertAxis.CREDIT, UsageAlertPeriod.WEEKLY, 100,
                        "공유 실습 키의 주간 금액 한도를 모두 사용했어요."),
                Arguments.of(UsageAlertAxis.CREDIT, UsageAlertPeriod.MONTHLY, 80,
                        "공유 실습 키의 월간 금액 한도를 80% 이상 사용했어요."),
                Arguments.of(UsageAlertAxis.CREDIT, UsageAlertPeriod.MONTHLY, 100,
                        "공유 실습 키의 월간 금액 한도를 모두 사용했어요."),
                Arguments.of(UsageAlertAxis.CREDIT, UsageAlertPeriod.TOTAL, 80,
                        "공유 실습 키의 총액 한도를 80% 이상 사용했어요."),
                Arguments.of(UsageAlertAxis.CREDIT, UsageAlertPeriod.TOTAL, 100,
                        "공유 실습 키의 총액 한도를 모두 사용했어요."));
    }

    @ParameterizedTest
    @MethodSource("usageMessages")
    void usageNoticeStatesTheKeyPeriodAndStageInOneSentence(
            UsageAlertAxis axis, UsageAlertPeriod period, int stage, String expected) {
        UsageAlertFacts facts = axis == UsageAlertAxis.TOKEN
                ? UsageAlertFacts.token(BigInteger.valueOf(stage), 100, NOW, NOW)
                : UsageAlertFacts.credit(new BigDecimal("100"),
                        stage == 80 ? new BigDecimal("20") : BigDecimal.ZERO, period, NOW, NOW);

        NotificationComposer.Composed notice = composer.usageThreshold(ID, "공유 실습 키", facts, NOW);

        assertThat(notice.body()).isEqualTo(expected);
        assertThat(notice.title()).isEqualTo("키 사용량 알림");
        assertThat(notice.eventId()).isEqualTo("llm.usage.threshold");
        assertThat(notice.linkPath()).isEqualTo("/console/llm-keys/" + ID);
        assertThat(notice.importance()).isEqualTo(NotificationImportance.NORMAL);
        assertThat(notice.payload()).isEqualTo(Map.of("keyId", ID, "axis", axis.name()));
        assertThat(facts.stage()).isEqualTo(stage);
        assertThat(facts.period()).isEqualTo(period);
    }

    @Test
    void requesterReceiptDoesNotRepeatTheSubmittedPurpose() {
        var notice = composer.compose(NotificationEvent.REQUEST_SUBMITTED, Map.of(
                "requestId", ID, "workspaceName", "공유 실습", "type", "VM",
                "purpose", "신청자가 이미 작성한 상세 목적"));

        assertThat(notice.body()).isEqualTo("'공유 실습'의 VM 신청이 접수됐어요. 검토가 끝나면 알려드릴게요.");
        assertThat(notice.linkPath()).isEqualTo("/console/requests/" + ID);
    }

    @Test
    void administratorReceiptKeepsThePurposeNeededForReview() {
        var notice = composer.compose(NotificationEvent.REQUEST_SUBMITTED, Map.of(
                "requestId", ID, "workspaceName", "공유 실습", "type", "VM",
                "purpose", "관리자가 검토할 상세 목적", "admin", true));

        assertThat(notice.body()).contains("관리자가 검토할 상세 목적", "검토해 주세요");
        assertThat(notice.linkPath()).isEqualTo("/admin/requests/" + ID);
    }

    @Test
    void workspaceDeletionReportsOnlyTheCompletedAction() {
        var notice = composer.compose(NotificationEvent.WORKSPACE_DELETED,
                Map.of("workspaceId", ID, "workspaceName", "공유 실습"));

        assertThat(notice.body()).isEqualTo("'공유 실습' 워크스페이스가 삭제됐어요.");
        assertThat(notice.payload()).isEqualTo(Map.of("workspaceId", ID, "workspaceName", "공유 실습"));
    }

    @Test
    void readyVmLinksToTheExistingConnectionInstructions() {
        var notice = composer.compose(NotificationEvent.VM_CREATE_DONE,
                Map.of("vmId", ID, "hostname", "practice-01"));

        assertThat(notice.body()).isEqualTo("VM 'practice-01'이 준비됐어요. 상세 화면에서 접속 방법을 확인해 주세요.");
        assertThat(notice.linkPath()).isEqualTo("/console/vms/" + ID);
        assertThat(notice.payload()).isEqualTo(Map.of("vmId", ID, "hostname", "practice-01"));
    }

    @Test
    void linkedAccountExplainsTheLoginActionWithoutTheInternalPasswordDecision() {
        var notice = composer.compose(NotificationEvent.ACCOUNT_IDENTITY_LINKED,
                Map.of("passwordCleared", true));

        assertThat(notice.body()).contains("구글 계정으로도 로그인", "본인이 연동한 것이 아니라면",
                "비밀번호로 로그인하려면 '비밀번호 찾기'에서 새로 설정해 주세요.");
        assertThat(notice.body()).doesNotContain("소유가 확인되지", "무효화", "이메일 인증을 마치지");
    }

    @Test
    void shortUsageBodyUsesTheExistingBrandedHtmlWithoutAListOrNewRenderer() {
        var facts = UsageAlertFacts.token(BigInteger.valueOf(80), 100, NOW, NOW);
        var notice = composer.usageThreshold(ID, "공유 <실습> 키", facts, NOW);
        String html = MailHtmlLayout.render(notice.title(), notice.body(),
                new MailHtmlLayout.Cta("콘솔에서 확인하기", "https://example.com" + notice.linkPath()));

        assertThat(html).contains("공유 &lt;실습&gt; 키의 일일 토큰 한도를 80% 이상 사용했어요.");
        assertThat(html).contains("https://example.com/console/llm-keys/" + ID);
        assertThat(html).doesNotContain("<ul", "서버 평가 시각", "관측 시각", "당시 한도");
        assertThat(html.split("<a ", -1)).hasSize(2);
    }
}
