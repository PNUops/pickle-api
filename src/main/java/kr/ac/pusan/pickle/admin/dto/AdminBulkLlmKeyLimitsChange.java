package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import org.jspecify.annotations.Nullable;

/**
 * The limits a bulk change touches on every key, and only those.
 *
 * <p>Absent means untouched, which is the whole difference from the single
 * replacement: that form carries all nine values, this one carries the ones
 * the administrator chose, and each key keeps the rest as it has them. A
 * scalar sent as {@code null} means what it means on the single form (service
 * default, or unlimited); the presence flags are what tell the two apart, the
 * same technique {@link AdminLlmKeyLimitsRequest} uses.
 *
 * <p>The three lists arrive as an operation rather than a value, so a change
 * can add one model to two hundred keys without knowing what each already
 * allows.
 */
public class AdminBulkLlmKeyLimitsChange {

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
    private @Nullable BigDecimal creditLimit;
    private boolean creditLimitSet;

    private @Nullable CreditLimitReset creditLimitReset;
    private boolean creditLimitResetSet;

    @Valid
    private @Nullable AdminBulkListChange creditAllowedModels;

    @Valid
    private @Nullable AdminBulkListChange creditDeniedModels;

    @Valid
    private @Nullable AdminBulkListChange passthroughEndpoints;

    @Schema(nullable = true, description = "분당 요청 한도. 생략하면 그대로 두고, null이면 서비스 기본값을 따릅니다.")
    public @Nullable Integer getRpm() {
        return rpm;
    }

    public void setRpm(@Nullable Integer rpm) {
        this.rpm = rpm;
        this.rpmSet = true;
    }

    @Schema(nullable = true, description = "분당 토큰 한도. 생략하면 그대로 두고, null이면 서비스 기본값을 따릅니다.")
    public @Nullable Integer getTpm() {
        return tpm;
    }

    public void setTpm(@Nullable Integer tpm) {
        this.tpm = tpm;
        this.tpmSet = true;
    }

    @Schema(nullable = true, description = "동시 요청 한도. 생략하면 그대로 두고, null이면 서비스 기본값을 따릅니다.")
    public @Nullable Integer getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(@Nullable Integer concurrency) {
        this.concurrency = concurrency;
        this.concurrencySet = true;
    }

    @Schema(nullable = true, description = "일일 토큰 한도. 생략하면 그대로 두고, null이면 무제한이며 0이면 토큰 축을 닫습니다.")
    public @Nullable Long getDailyTokens() {
        return dailyTokens;
    }

    public void setDailyTokens(@Nullable Long dailyTokens) {
        this.dailyTokens = dailyTokens;
        this.dailyTokensSet = true;
    }

    @Schema(description = "금액 한도(USD 크레딧). 생략하면 그대로 둡니다. null은 허용하지 않고, 유료 모델을 닫으려면 0을 보냅니다.")
    public @Nullable BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(@Nullable BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
        this.creditLimitSet = true;
    }

    @Schema(nullable = true, description = "금액 한도 리셋 창. 생략하면 그대로 두고, null이면 리셋 없는 총액 상한입니다.")
    public @Nullable CreditLimitReset getCreditLimitReset() {
        return creditLimitReset;
    }

    public void setCreditLimitReset(@Nullable CreditLimitReset creditLimitReset) {
        this.creditLimitReset = creditLimitReset;
        this.creditLimitResetSet = true;
    }

    @Schema(description = "유료 모델 허용 목록에 적용할 변경. 생략하면 그대로 둡니다.")
    public @Nullable AdminBulkListChange getCreditAllowedModels() {
        return creditAllowedModels;
    }

    public void setCreditAllowedModels(@Nullable AdminBulkListChange creditAllowedModels) {
        this.creditAllowedModels = creditAllowedModels;
    }

    @Schema(description = "유료 모델 차단 목록에 적용할 변경. 생략하면 그대로 둡니다.")
    public @Nullable AdminBulkListChange getCreditDeniedModels() {
        return creditDeniedModels;
    }

    public void setCreditDeniedModels(@Nullable AdminBulkListChange creditDeniedModels) {
        this.creditDeniedModels = creditDeniedModels;
    }

    @Schema(description = "기능 권한 목록에 적용할 변경. 생략하면 그대로 둡니다. 값은 images와 embeddings입니다.")
    public @Nullable AdminBulkListChange getPassthroughEndpoints() {
        return passthroughEndpoints;
    }

    public void setPassthroughEndpoints(@Nullable AdminBulkListChange passthroughEndpoints) {
        this.passthroughEndpoints = passthroughEndpoints;
    }

    @Schema(hidden = true)
    public boolean isRpmSet() {
        return rpmSet;
    }

    @Schema(hidden = true)
    public boolean isTpmSet() {
        return tpmSet;
    }

    @Schema(hidden = true)
    public boolean isConcurrencySet() {
        return concurrencySet;
    }

    @Schema(hidden = true)
    public boolean isDailyTokensSet() {
        return dailyTokensSet;
    }

    @Schema(hidden = true)
    public boolean isCreditLimitSet() {
        return creditLimitSet;
    }

    @Schema(hidden = true)
    public boolean isCreditLimitResetSet() {
        return creditLimitResetSet;
    }

    /** True when the change names nothing at all, which is a form with no question in it. */
    @Schema(hidden = true)
    public boolean isEmpty() {
        return !rpmSet && !tpmSet && !concurrencySet && !dailyTokensSet && !creditLimitSet
                && !creditLimitResetSet && creditAllowedModels == null
                && creditDeniedModels == null && passthroughEndpoints == null;
    }
}
