package kr.ac.pusan.pickle.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.ProblemJsonWriter;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** 401 (contract: components/responses/Unauthorized, code AUTH_TOKEN_INVALID). */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemJsonWriter problemJsonWriter;

    public ProblemAuthenticationEntryPoint(ProblemJsonWriter problemJsonWriter) {
        this.problemJsonWriter = problemJsonWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        problemJsonWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                ErrorCodes.AUTH_TOKEN_INVALID, "인증이 필요합니다",
                "액세스 토큰이 없거나 만료되었습니다. 토큰을 갱신한 뒤 다시 시도해 주세요.");
    }
}
