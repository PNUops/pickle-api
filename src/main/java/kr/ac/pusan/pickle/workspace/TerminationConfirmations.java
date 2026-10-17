package kr.ac.pusan.pickle.workspace;

import java.util.List;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.workspace.dto.TerminationConfirmation;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

public final class TerminationConfirmations {
    public static void requireConfirmed(String fingerprint, @Nullable TerminationConfirmation confirmation) {
        if (confirmation == null || !confirmation.confirmed() || confirmation.fingerprint() == null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("confirmation", "현재 대상과 종료 영향을 명시적으로 확인해 주세요.")));
        }
        if (!fingerprint.equals(confirmation.fingerprint())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.TERMINATION_IMPACT_CHANGED, "종료 영향이 변경되었습니다",
                    "현재 대상과 상태를 다시 조회하고 확인해 주세요.");
        }
    }
    private TerminationConfirmations() { }
}
