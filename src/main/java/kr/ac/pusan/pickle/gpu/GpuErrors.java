package kr.ac.pusan.pickle.gpu;

import java.util.List;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import org.springframework.http.HttpStatus;

public final class GpuErrors {
    private GpuErrors() {}
    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, "리소스를 찾을 수 없습니다", "해당 GPU 리소스가 존재하지 않습니다.");
    }
    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.WORKSPACE_ROLE_INSUFFICIENT, "권한이 없습니다", "이 작업을 수행할 권한이 없습니다.");
    }
    public static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, "GPU 작업을 수행할 수 없습니다", detail);
    }
    public static ApiException invalid(String field, String detail) {
        return ApiException.validationFailed(List.of(new FieldValidationError(field, detail)));
    }
}
