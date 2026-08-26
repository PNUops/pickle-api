package kr.ac.pusan.pickle.auth;

import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.user.User;
import org.springframework.http.HttpStatus;

/**
 * Guard for the operations that ask the account holder to re-type their current
 * password. Since V89 an account may never have had one — it was created
 * through an external identity — and every such operation has to say so rather
 * than compare against null.
 *
 * <p>It lives in one place because the answer has to be one sentence: four
 * services ask this question, and a copy per service is four chances for them
 * to tell the user something different about the same account.
 *
 * <p>This is not the login path. {@code AuthService.login} must stay silent
 * about which addresses have no password (uniform 401); here the caller is
 * already authenticated as the account in question, so naming its own state
 * discloses nothing and is the only way out of the dead end.
 */
public final class PasswordCredential {

    private PasswordCredential() {
    }

    /** Throws 409 when the account has no password to compare against. */
    public static void require(User user) {
        if (!user.hasPassword()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.AUTH_PASSWORD_NOT_SET,
                    "비밀번호가 설정되어 있지 않습니다",
                    "구글 계정으로 가입한 계정입니다. 내 정보에서 비밀번호를 먼저 설정해 주세요.");
        }
    }

    /** Throws 409 when the account already has a password to change instead. */
    public static void requireAbsent(User user) {
        if (user.hasPassword()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.AUTH_PASSWORD_ALREADY_SET,
                    "비밀번호가 이미 설정되어 있습니다",
                    "이 계정에는 비밀번호가 있습니다. 현재 비밀번호를 입력해 변경해 주세요.");
        }
    }
}
