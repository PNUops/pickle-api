package kr.ac.pusan.pickle.mail;

import java.time.Duration;
import kr.ac.pusan.pickle.config.AuthProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Builds the Korean signup verification / password-reset / already-registered
 * notice mails, as a text part and a branded HTML part assembled by
 * {@link MailHtmlLayout} (no template engine).
 *
 * <p>The body templates name no medium — the link is a button in the HTML part
 * and a line of its own in the text part, which is also what the dev spool is
 * grepped for.</p>
 */
@Component
public class VerificationMailComposer {

    private static final String SIGNATURE = "\n— Pickle 운영팀\n";

    private static final String SUBJECT = "[Pickle] 부산대학교 클라우드 플랫폼 이메일 인증";
    private static final String HEADING = "이메일 인증";

    private static final String BODY_TEMPLATE = """
            안녕하세요, %s님.

            Pickle(부산대학교 클라우드 플랫폼) 가입을 완료하려면 이메일 인증을
            진행해 주세요. 인증 링크는 %s 동안 1회만 사용할 수 있습니다.

            본인이 가입을 요청하지 않았다면 이 메일을 무시하셔도 됩니다.""";

    private static final String RESET_SUBJECT = "[Pickle] 부산대학교 클라우드 플랫폼 비밀번호 재설정";
    private static final String RESET_HEADING = "비밀번호 재설정";

    private static final String RESET_BODY_TEMPLATE = """
            안녕하세요, %s님.

            비밀번호 재설정을 요청하셨습니다. 아래에서 새 비밀번호를 설정해
            주세요. 재설정 링크는 %s 동안 1회만 사용할 수 있습니다.

            본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다. 비밀번호는
            변경되지 않습니다.""";

    private static final String ALREADY_REGISTERED_SUBJECT =
            "[Pickle] 부산대학교 클라우드 플랫폼 가입 안내";
    private static final String ALREADY_REGISTERED_HEADING = "가입 안내";

    /**
     * Sent instead of creating an account when the address is already on file.
     * Carries no link or token — not even a console one: the signup response is
     * identical for every address, so this mail must not become a way to
     * confirm one either, and a clickable link is a prefetchable signal.
     */
    private static final String ALREADY_REGISTERED_BODY = """
            안녕하세요.

            이 주소로 Pickle(부산대학교 클라우드 플랫폼) 가입 요청이 접수되었지만,
            이미 가입된 계정이 있어 새로 가입되지는 않았습니다.

            비밀번호를 잊으셨다면 로그인 화면의 '비밀번호 찾기'에서 재설정할 수
            있습니다.

            본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다. 계정에는 아무런
            변경이 없습니다.""";

    private final AuthProperties authProperties;

    public VerificationMailComposer(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    public MailMessage compose(String email, String name, String token) {
        String link = linkWithToken(authProperties.verificationBaseUrl(), token);
        String body = BODY_TEMPLATE.formatted(name,
                humanizeTtl(authProperties.verificationTokenTtl()));
        return new MailMessage(email, SUBJECT, textWithLink(body, link),
                MailHtmlLayout.render(HEADING, body,
                        new MailHtmlLayout.Cta("이메일 인증하기", link)));
    }

    public MailMessage composePasswordReset(String email, String name, String token) {
        String link = linkWithToken(authProperties.passwordResetBaseUrl(), token);
        String body = RESET_BODY_TEMPLATE.formatted(name,
                humanizeTtl(authProperties.passwordResetTokenTtl()));
        return new MailMessage(email, RESET_SUBJECT, textWithLink(body, link),
                MailHtmlLayout.render(RESET_HEADING, body,
                        new MailHtmlLayout.Cta("새 비밀번호 설정하기", link)));
    }

    public MailMessage composeAlreadyRegistered(String email) {
        return new MailMessage(email, ALREADY_REGISTERED_SUBJECT,
                ALREADY_REGISTERED_BODY + "\n" + SIGNATURE,
                MailHtmlLayout.render(ALREADY_REGISTERED_HEADING, ALREADY_REGISTERED_BODY));
    }

    /**
     * Text part: body, the bare link on a line of its own, signature. The link
     * line is what a client without HTML has to work with, and what the dev
     * spool is read for.
     */
    private static String textWithLink(String body, String link) {
        return body + "\n\n" + link + "\n" + SIGNATURE;
    }

    /** Duration as the mail says it: {@code 24시간}, {@code 30분}, {@code 1시간 30분}. */
    static String humanizeTtl(Duration ttl) {
        long hours = ttl.toHours();
        int minutes = ttl.toMinutesPart();
        if (hours > 0 && minutes > 0) {
            return "%d시간 %d분".formatted(hours, minutes);
        }
        return hours > 0 ? "%d시간".formatted(hours) : "%d분".formatted(minutes);
    }

    private static String linkWithToken(String baseUrl, String token) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("token", token)
                .build()
                .toUriString();
    }
}
