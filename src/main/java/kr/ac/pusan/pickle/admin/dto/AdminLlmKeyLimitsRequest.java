package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import org.jspecify.annotations.Nullable;

/**
 * Full replacement of the seven limits on an administered LLM key.
 *
 * <p>Two of them spell "unset" in opposite directions, which is the trap in
 * this class: {@code creditLimit} refuses null because money has no unlimited,
 * while a null {@code creditAllowedModels} is exactly unrestricted.
 */
public class AdminLlmKeyLimitsRequest {

    @Min(value = 1, message = "분당 요청 수는 1 이상이어야 합니다.")
    @Max(value = 10000, message = "분당 요청 수가 너무 큽니다.")
    private @Nullable Integer rpm;
    private boolean rpmSet;

    @Min(value = 1, message = "분당 토큰 수는 1 이상이어야 합니다.")
    private @Nullable Integer tpm;
    private boolean tpmSet;

    @Min(value = 1, message = "동시 요청 수는 1 이상이어야 합니다.")
    @Max(value = 100, message = "동시 요청 수가 너무 큽니다.")
    private @Nullable Integer concurrency;
    private boolean concurrencySet;

    @Min(value = 0, message = "일일 토큰 수는 0 이상이어야 합니다.")
    private @Nullable Long dailyTokens;
    private boolean dailyTokensSet;

    @DecimalMin(value = "0", message = "금액 한도는 0 이상이어야 합니다.")
    @Digits(integer = 10, fraction = 2, message = "금액 한도는 소수점 둘째 자리까지 입력해 주세요.")
    @NotNull(message = "금액 한도는 필수입니다. 유료 모델을 닫으려면 0을 보내 주세요.")
    private BigDecimal creditLimit;
    private boolean creditLimitSet;

    private @Nullable CreditLimitReset creditLimitReset;
    private boolean creditLimitResetSet;

    @Size(max = 50, message = "모델은 최대 50개까지 허용할 수 있습니다.")
    private @Nullable List<String> creditAllowedModels;
    private boolean creditAllowedModelsSet;

    private @Nullable UUID openrouterAccountId;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "분당 요청 한도. null이면 서비스 기본값을 따릅니다.")
    public @Nullable Integer getRpm() {
        return rpm;
    }

    public void setRpm(@Nullable Integer rpm) {
        this.rpm = rpm;
        this.rpmSet = true;
    }

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "분당 토큰 한도. null이면 서비스 기본값을 따릅니다.")
    public @Nullable Integer getTpm() {
        return tpm;
    }

    public void setTpm(@Nullable Integer tpm) {
        this.tpm = tpm;
        this.tpmSet = true;
    }

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "동시 요청 한도. null이면 서비스 기본값을 따릅니다.")
    public @Nullable Integer getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(@Nullable Integer concurrency) {
        this.concurrency = concurrency;
        this.concurrencySet = true;
    }

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "일일 토큰 한도. null이면 무제한이고 0이면 토큰 축을 닫습니다.")
    public @Nullable Long getDailyTokens() {
        return dailyTokens;
    }

    public void setDailyTokens(@Nullable Long dailyTokens) {
        this.dailyTokens = dailyTokens;
        this.dailyTokensSet = true;
    }

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "금액 한도(USD 크레딧). 0이면 유료 모델을 닫습니다.")
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
        this.creditLimitSet = true;
    }

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "금액 한도 리셋 창. null이면 리셋 없는 총액 상한입니다.")
    public @Nullable CreditLimitReset getCreditLimitReset() {
        return creditLimitReset;
    }

    public void setCreditLimitReset(@Nullable CreditLimitReset creditLimitReset) {
        this.creditLimitReset = creditLimitReset;
        this.creditLimitResetSet = true;
    }

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "유료 모델 허용 목록. 빈 배열이나 null이면 제한이 없습니다. 금액 "
                    + "한도와 달리 null이 0을 뜻하지 않습니다. 자체 서빙 모델은 이 값에 "
                    + "영향을 받지 않습니다.")
    public @Nullable List<String> getCreditAllowedModels() {
        return creditAllowedModels;
    }

    public void setCreditAllowedModels(@Nullable List<String> creditAllowedModels) {
        this.creditAllowedModels = creditAllowedModels;
        this.creditAllowedModelsSet = true;
    }

    @Schema(description = "유료 모델을 결제할 사업 계정. 생략하거나 null이면 기존 연결을 유지합니다.")
    public @Nullable UUID getOpenrouterAccountId() {
        return openrouterAccountId;
    }

    public void setOpenrouterAccountId(@Nullable UUID openrouterAccountId) {
        this.openrouterAccountId = openrouterAccountId;
    }

    @Schema(hidden = true)
    public boolean isComplete() {
        return rpmSet && tpmSet && concurrencySet && dailyTokensSet
                && creditLimitSet && creditLimitResetSet && creditAllowedModelsSet;
    }
}
