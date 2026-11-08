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
@tools.jackson.databind.annotation.JsonSerialize(using = AdminBulkLlmKeyLimitsChange.SpecifiedFieldsSerializer.class)
public class AdminBulkLlmKeyLimitsChange {

    /** Snapshot serialization keeps omitted scalars distinct from explicit nulls. */
    public static class SpecifiedFieldsSerializer extends tools.jackson.databind.ValueSerializer<AdminBulkLlmKeyLimitsChange> {
        @Override
        public void serialize(AdminBulkLlmKeyLimitsChange value, tools.jackson.core.JsonGenerator generator,
                tools.jackson.databind.SerializationContext context) {
            generator.writeStartObject();
            if (value.rpmSet) { context.defaultSerializeProperty("rpm", value.rpm, generator); }
            if (value.tpmSet) { context.defaultSerializeProperty("tpm", value.tpm, generator); }
            if (value.concurrencySet) { context.defaultSerializeProperty("concurrency", value.concurrency, generator); }
            if (value.dailyTokensSet) { context.defaultSerializeProperty("dailyTokens", value.dailyTokens, generator); }
            if (value.creditLimitSet) { context.defaultSerializeProperty("creditLimit", value.creditLimit, generator); }
            if (value.creditLimitResetSet) { context.defaultSerializeProperty("creditLimitReset", value.creditLimitReset, generator); }
            if (value.creditAllowedModels != null) { context.defaultSerializeProperty("creditAllowedModels", value.creditAllowedModels, generator); }
            if (value.creditDeniedModels != null) { context.defaultSerializeProperty("creditDeniedModels", value.creditDeniedModels, generator); }
            if (value.passthroughEndpoints != null) { context.defaultSerializeProperty("passthroughEndpoints", value.passthroughEndpoints, generator); }
            generator.writeEndObject();
        }
    }


    @Min(value = 1, message = "분당 요청 수는 1 이상으로 입력해 주세요.")
    @Max(value = 10000, message = "분당 요청 수는 10000 이하로 입력해 주세요.")
    private @Nullable Integer rpm;
    private boolean rpmSet;

    @Min(value = 1, message = "분당 토큰 수는 1 이상으로 입력해 주세요.")
    private @Nullable Integer tpm;
    private boolean tpmSet;

    @Min(value = 1, message = "동시 요청 수는 1 이상으로 입력해 주세요.")
    @Max(value = 100, message = "동시 요청 수는 100 이하로 입력해 주세요.")
    private @Nullable Integer concurrency;
    private boolean concurrencySet;

    @Min(value = 0, message = "일일 토큰 수는 0 이상으로 입력해 주세요.")
    private @Nullable Long dailyTokens;
    private boolean dailyTokensSet;

    @DecimalMin(value = "0", message = "금액 한도는 0 이상으로 입력해 주세요.")
    @Digits(integer = 10, fraction = 2, message = "금액 한도는 소수점 둘째 자리까지 입력해 주세요.")
    @kr.ac.pusan.pickle.common.openapi.NonNullWhenPresent
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

    @Schema(nullable = true, description = "분당 요청 한도예요. 생략하면 유지하고 null이면 서비스 기본값을 따라요.")
    public @Nullable Integer getRpm() {
        return rpm;
    }

    public void setRpm(@Nullable Integer rpm) {
        this.rpm = rpm;
        this.rpmSet = true;
    }

    @Schema(nullable = true, description = "분당 토큰 한도예요. 생략하면 유지하고 null이면 서비스 기본값을 따라요.")
    public @Nullable Integer getTpm() {
        return tpm;
    }

    public void setTpm(@Nullable Integer tpm) {
        this.tpm = tpm;
        this.tpmSet = true;
    }

    @Schema(nullable = true, description = "동시 요청 한도예요. 생략하면 유지하고 null이면 서비스 기본값을 따라요.")
    public @Nullable Integer getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(@Nullable Integer concurrency) {
        this.concurrency = concurrency;
        this.concurrencySet = true;
    }

    @Schema(nullable = true, description = "일일 토큰 한도예요. 생략하면 유지하고 null이면 무제한이며 0이면 토큰 축을 닫아요.")
    public @Nullable Long getDailyTokens() {
        return dailyTokens;
    }

    public void setDailyTokens(@Nullable Long dailyTokens) {
        this.dailyTokens = dailyTokens;
        this.dailyTokensSet = true;
    }

    @Schema(description = "금액 한도예요(USD 크레딧). 생략하면 유지해요. null은 허용하지 않으며 유료 모델을 닫으려면 0을 보내요.")
    public @Nullable BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(@Nullable BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
        this.creditLimitSet = true;
    }

    @Schema(nullable = true, description = "금액 한도 리셋 창이에요. 생략하면 유지하고 null이면 리셋 없는 총액 상한이에요.")
    public @Nullable CreditLimitReset getCreditLimitReset() {
        return creditLimitReset;
    }

    public void setCreditLimitReset(@Nullable CreditLimitReset creditLimitReset) {
        this.creditLimitReset = creditLimitReset;
        this.creditLimitResetSet = true;
    }

    @Schema(description = "유료 모델 허용 목록에 적용할 변경이며 생략하면 유지해요.")
    public @Nullable AdminBulkListChange getCreditAllowedModels() {
        return creditAllowedModels;
    }

    public void setCreditAllowedModels(@Nullable AdminBulkListChange creditAllowedModels) {
        this.creditAllowedModels = creditAllowedModels;
    }

    @Schema(description = "유료 모델 차단 목록에 적용할 변경이며 생략하면 유지해요.")
    public @Nullable AdminBulkListChange getCreditDeniedModels() {
        return creditDeniedModels;
    }

    public void setCreditDeniedModels(@Nullable AdminBulkListChange creditDeniedModels) {
        this.creditDeniedModels = creditDeniedModels;
    }

    @Schema(description = "기능 권한 목록에 적용할 변경이며 생략하면 유지해요. 현재 변경 요청에는 chat, web_search, pdf_ocr, images, embeddings, rerank, speech, transcriptions, batches를 지정할 수 있어요. 결과 목록에 web_search나 pdf_ocr가 있으면 chat도, batches가 있으면 chat이나 embeddings도 함께 있어야 해요.")
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
