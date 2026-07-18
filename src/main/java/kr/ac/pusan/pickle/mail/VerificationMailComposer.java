package kr.ac.pusan.pickle.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Builds the Korean signup verification / password-reset mails (text templates). */
@Component
public class VerificationMailComposer {

    private static final String SUBJECT = "[Pickle] 부산대학교 클라우드 플랫폼 이메일 인증";

    private static final String BODY_TEMPLATE = """
            안녕하세요, %s님.

            Pickle(부산대학교 클라우드 플랫폼) 가입을 완료하려면 아래 링크를 열어
            이메일 인증을 진행해 주세요. 링크는 24시간 동안 1회만 사용할 수 있습니다.

            %s

            본인이 가입을 요청하지 않았다면 이 메일을 무시하셔도 됩니다.

            — Pickle 운영팀
            """;

    private static final String RESET_SUBJECT = "[Pickle] 부산대학교 클라우드 플랫폼 비밀번호 재설정";

    private static final String RESET_BODY_TEMPLATE = """
            안녕하세요, %s님.

            비밀번호 재설정을 요청하셨습니다. 아래 링크를 열어 새 비밀번호를
            설정해 주세요. 링크는 30분 동안 1회만 사용할 수 있습니다.

            %s

            본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다. 비밀번호는
            변경되지 않습니다.

            — Pickle 운영팀
            """;

    private final String verificationBaseUrl;
    private final String passwordResetBaseUrl;

    public VerificationMailComposer(
            @Value("${pickle.auth.verification-base-url}") String verificationBaseUrl,
            @Value("${pickle.auth.password-reset-base-url}") String passwordResetBaseUrl) {
        this.verificationBaseUrl = verificationBaseUrl;
        this.passwordResetBaseUrl = passwordResetBaseUrl;
    }

    public MailMessage compose(String email, String name, String token) {
        return new MailMessage(email, SUBJECT,
                BODY_TEMPLATE.formatted(name, linkWithToken(verificationBaseUrl, token)));
    }

    public MailMessage composePasswordReset(String email, String name, String token) {
        return new MailMessage(email, RESET_SUBJECT,
                RESET_BODY_TEMPLATE.formatted(name, linkWithToken(passwordResetBaseUrl, token)));
    }

    private static String linkWithToken(String baseUrl, String token) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("token", token)
                .build()
                .toUriString();
    }
}
