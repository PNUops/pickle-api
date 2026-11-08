package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.List;
import kr.ac.pusan.pickle.llm.CreditModelPatterns;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountStatus;
import org.jspecify.annotations.Nullable;

/** Presence-tracked partial update; explicit null clears optional references. */
public class UpdateOpenRouterAccountRequest {

    @Size(max = 120)
    private String name;
    private boolean nameSet;

    @Size(max = 500)
    private @Nullable String program;
    private boolean programSet;

    @Size(max = 500)
    private @Nullable String contact;
    private boolean contactSet;

    private OpenRouterAccountStatus status;
    private boolean statusSet;

    @Size(max = 50, message = "허용할 모델은 50개 이내로 지정해 주세요.")
    private @Nullable List<String> defaultCreditAllowedModels;
    private boolean defaultCreditAllowedModelsSet;

    @Size(max = 50, message = "차단할 모델은 50개 이내로 지정해 주세요.")
    private @Nullable List<String> defaultCreditDeniedModels;
    private boolean defaultCreditDeniedModelsSet;

    @Schema(description = "새 사업 계정 이름이에요. 생략하면 유지하고 null은 허용하지 않아요.")
    public String getName() { return name; }
    public void setName(@Nullable String name) { this.name = name; this.nameSet = true; }
    @Schema(description = "새 사업이에요. 생략하면 유지하고 null이면 비워요.")
    public @Nullable String getProgram() { return program; }
    public void setProgram(@Nullable String value) {
        program = value; programSet = true;
    }
    @Schema(description = "새 담당자예요. 생략하면 유지하고 null이면 비워요.")
    public @Nullable String getContact() { return contact; }
    public void setContact(@Nullable String value) {
        contact = value; contactSet = true;
    }
    @Schema(description = "새 계정 상태예요. 생략하면 유지하고 null은 허용하지 않아요.")
    public OpenRouterAccountStatus getStatus() { return status; }
    public void setStatus(@Nullable OpenRouterAccountStatus status) {
        this.status = status; statusSet = true;
    }

    @Schema(description = "새 유료 모델 허용 목록의 기본값이에요. 생략하면 유지하고 null이나 빈 배열이면 "
            + "기본값을 비워요. 이미 발급된 키에는 이 변경을 "
            + "자동 적용하지 않아요."
            + CreditModelPatterns.ALLOW_PATTERN_DESCRIPTION)
    public @Nullable List<String> getDefaultCreditAllowedModels() {
        return defaultCreditAllowedModels;
    }

    public void setDefaultCreditAllowedModels(@Nullable List<String> value) {
        defaultCreditAllowedModels = value; defaultCreditAllowedModelsSet = true;
    }

    @Schema(description = "새 유료 모델 차단 목록의 기본값이에요. 생략하면 유지하고 null이나 빈 배열이면 "
            + "기본값을 비워요. 이미 발급된 키에는 이 변경을 "
            + "자동 적용하지 않아요."
            + CreditModelPatterns.DENY_PATTERN_DESCRIPTION)
    public @Nullable List<String> getDefaultCreditDeniedModels() {
        return defaultCreditDeniedModels;
    }

    public void setDefaultCreditDeniedModels(@Nullable List<String> value) {
        defaultCreditDeniedModels = value; defaultCreditDeniedModelsSet = true;
    }

    @Schema(hidden = true) public boolean isNameSet() { return nameSet; }
    @Schema(hidden = true) public boolean isProgramSet() { return programSet; }
    @Schema(hidden = true) public boolean isContactSet() { return contactSet; }
    @Schema(hidden = true) public boolean isStatusSet() { return statusSet; }
    @Schema(hidden = true) public boolean isDefaultCreditAllowedModelsSet() {
        return defaultCreditAllowedModelsSet;
    }
    @Schema(hidden = true) public boolean isDefaultCreditDeniedModelsSet() {
        return defaultCreditDeniedModelsSet;
    }
    @Schema(hidden = true) public boolean hasAny() {
        return nameSet || programSet || contactSet || statusSet
                || defaultCreditAllowedModelsSet || defaultCreditDeniedModelsSet;
    }
}
