package kr.ac.pusan.pickle.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.ProblemJsonWriter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** 403 (contract: components/responses/Forbidden, code ACCESS_DENIED). */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemJsonWriter problemJsonWriter;

    public ProblemAccessDeniedHandler(ProblemJsonWriter problemJsonWriter) {
        this.problemJsonWriter = problemJsonWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        problemJsonWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN,
                ErrorCodes.ACCESS_DENIED, "접근 권한이 없습니다", "이 작업을 수행할 권한이 없습니다.");
    }
}
