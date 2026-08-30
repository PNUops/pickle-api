package kr.ac.pusan.pickle.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import kr.ac.pusan.pickle.config.AuthProperties;
import org.junit.jupiter.api.Test;

/**
 * The account mails carry the link twice over: as a button in the HTML part
 * and as a bare line in the text part, which is what a client without HTML
 * shows and what the dev mock-mail spool is grepped for. The already-registered
 * notice carries neither, in either part.
 */
class VerificationMailComposerTest {

    private static final AuthProperties PROPS = new AuthProperties(
            Duration.ofDays(14), Duration.ofHours(24), Duration.ofMinutes(30),
            "https://pickle.pusan.ac.kr/verify-email",
            "https://pickle.pusan.ac.kr/reset-password");

    private final VerificationMailComposer composer = new VerificationMailComposer(PROPS);

    @Test
    void verificationMailCarriesTheLinkInBothPartsButAnchorsItOnce() {
        MailMessage mail = composer.compose("a@pusan.ac.kr", "홍길동", "TOK123");

        assertThat(mail.textBody())
                .contains("홍길동님")
                .contains("https://pickle.pusan.ac.kr/verify-email?token=TOK123")
                .contains("부산대학교 정보컴퓨터공학부 PNUops")
                .contains("본 메일은 발신 전용입니다. 회신하신 내용은 확인되지 않습니다.")
                .doesNotContain("Pickle 운영팀")
                .doesNotContain("운영: 부산대학교 정보컴퓨터공학부 PNUops");
        assertThat(mail.hasHtml()).isTrue();
        assertThat(mail.htmlBody())
                .contains("이메일 인증하기")
                .contains("token=TOK123");
        // exactly one anchor: a prefetching gateway must not be handed the
        // one-time token twice
        assertThat(countOf(mail.htmlBody(), "<a ")).isEqualTo(1);
    }

    @Test
    void passwordResetMailReadsItsTtlFromConfiguration() {
        MailMessage mail = composer.composePasswordReset("a@pusan.ac.kr", "홍길동", "RST999");

        assertThat(mail.textBody())
                .contains("30분 동안")
                .contains("https://pickle.pusan.ac.kr/reset-password?token=RST999");
        assertThat(mail.htmlBody()).contains("새 비밀번호 설정하기");
        assertThat(countOf(mail.htmlBody(), "<a ")).isEqualTo(1);
    }

    @Test
    void verificationTtlWordingFollowsTheConfiguredDuration() {
        assertThat(composer.compose("a@pusan.ac.kr", "홍", "T").textBody())
                .contains("24시간 동안");

        AuthProperties shortTtl = new AuthProperties(Duration.ofDays(14), Duration.ofMinutes(90),
                Duration.ofMinutes(30), PROPS.verificationBaseUrl(), PROPS.passwordResetBaseUrl());
        assertThat(new VerificationMailComposer(shortTtl)
                .compose("a@pusan.ac.kr", "홍", "T").textBody())
                .contains("1시간 30분 동안");
    }

    @Test
    void alreadyRegisteredNoticeCarriesNoLinkInEitherPart() {
        MailMessage mail = composer.composeAlreadyRegistered("a@pusan.ac.kr");

        assertThat(mail.textBody()).contains("이미 가입된 계정").doesNotContain("token=");
        assertThat(mail.htmlBody())
                .doesNotContain("token=")
                .doesNotContain("<a ")
                .contains("가입 안내");
    }

    @Test
    void ttlWordingDropsTheEmptyHalf() {
        assertThat(VerificationMailComposer.humanizeTtl(Duration.ofHours(24))).isEqualTo("24시간");
        assertThat(VerificationMailComposer.humanizeTtl(Duration.ofMinutes(30))).isEqualTo("30분");
        assertThat(VerificationMailComposer.humanizeTtl(Duration.ofMinutes(90)))
                .isEqualTo("1시간 30분");
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
