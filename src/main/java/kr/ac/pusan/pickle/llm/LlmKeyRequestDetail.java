package kr.ac.pusan.pickle.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * What an LLM API key request asks for, and what the reviewer granted.
 *
 * <p>Keyed by its request, like the VM's. Every field is optional on both
 * sides: a key that names no limits is asking for the service's defaults, and
 * an approval that grants none is granting exactly that. The row's existence is
 * therefore the whole assertion the approved-grant trigger makes for this type
 * — there is no per-field granted specification to check.
 */
@Entity
@Table(name = "llm_key_request_details")
public class LlmKeyRequestDetail {

    @Id
    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "req_rpm")
    private @Nullable Integer reqRpm;

    @Column(name = "req_tpm")
    private @Nullable Integer reqTpm;

    @Column(name = "req_daily_tokens")
    private @Nullable Long reqDailyTokens;

    /**
     * 신청자가 고른 축. 한도가 비어 있는 것으로는 알 수 없다 -- 빈 한도는 서비스
     * 기본값이라는 뜻이지 그 축을 안 쓰겠다는 뜻이 아니다.
     */
    @Column(name = "req_use_campus", nullable = false)
    private boolean reqUseCampus = true;

    @Column(name = "req_use_commercial", nullable = false)
    private boolean reqUseCommercial;

    @Column(name = "req_credit_limit")
    private @Nullable BigDecimal reqCreditLimit;

    @Column(name = "granted_rpm")
    private @Nullable Integer grantedRpm;

    @Column(name = "granted_tpm")
    private @Nullable Integer grantedTpm;

    @Column(name = "granted_concurrency")
    private @Nullable Integer grantedConcurrency;

    @Column(name = "granted_daily_tokens")
    private @Nullable Long grantedDailyTokens;

    @Column(name = "granted_credit_limit")
    private @Nullable BigDecimal grantedCreditLimit;

    @Column(name = "granted_credit_limit_reset")
    @Enumerated(EnumType.STRING)
    private @Nullable CreditLimitReset grantedCreditLimitReset;

    @Column(name = "granted_openrouter_account_id")
    private @Nullable Long grantedOpenrouterAccountId;

    /**
     * The money-axis model allow list the reviewer granted, as a JSON array.
     * Empty means unrestricted. Unlike the numbers above it has no requested
     * counterpart: which models to open is the reviewer's decision, not
     * something an applicant asks for.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "granted_credit_allowed_models", nullable = false, columnDefinition = "jsonb")
    private String grantedCreditAllowedModels = CreditModelPatterns.EMPTY_JSON;

    /**
     * The money-axis model deny list the reviewer granted, as a JSON array.
     * Empty means nothing was blocked. Like the allow list it has no requested
     * counterpart, and unlike it there is no rule tying it to a positive
     * amount: a refusal holds at any amount.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "granted_credit_denied_models", nullable = false, columnDefinition = "jsonb")
    private String grantedCreditDeniedModels = CreditModelPatterns.EMPTY_JSON;

    protected LlmKeyRequestDetail() {
    }

    public LlmKeyRequestDetail(long requestId, @Nullable Integer reqRpm,
            @Nullable Integer reqTpm, @Nullable Long reqDailyTokens, boolean reqUseCampus,
            boolean reqUseCommercial, @Nullable BigDecimal reqCreditLimit) {
        this.requestId = requestId;
        this.reqRpm = reqRpm;
        this.reqTpm = reqTpm;
        this.reqDailyTokens = reqDailyTokens;
        this.reqUseCampus = reqUseCampus;
        this.reqUseCommercial = reqUseCommercial;
        this.reqCreditLimit = reqCreditLimit;
    }

    public boolean isReqUseCampus() {
        return reqUseCampus;
    }

    public boolean isReqUseCommercial() {
        return reqUseCommercial;
    }

    public @Nullable BigDecimal getReqCreditLimit() {
        return reqCreditLimit;
    }

    /**
     * Records what the reviewer granted. Nulls mean the service defaults —
     * except the credit limit, whose absence means 0: the money axis is
     * closed unless a reviewer deliberately opened it.
     */
    public void grant(@Nullable Integer rpm, @Nullable Integer tpm, @Nullable Integer concurrency,
            @Nullable Long dailyTokens, @Nullable BigDecimal creditLimit,
            @Nullable CreditLimitReset creditLimitReset) {
        grant(rpm, tpm, concurrency, dailyTokens, creditLimit, creditLimitReset, null,
                CreditModelPatterns.EMPTY_JSON, CreditModelPatterns.EMPTY_JSON);
    }

    /**
     * The last two arguments are the allowed list and then the denied list. They
     * are both JSON arrays of the same shape, so only their order tells them
     * apart and transposing them inverts the reviewer's decision.
     */
    public void grant(@Nullable Integer rpm, @Nullable Integer tpm, @Nullable Integer concurrency,
            @Nullable Long dailyTokens, @Nullable BigDecimal creditLimit,
            @Nullable CreditLimitReset creditLimitReset, @Nullable Long openrouterAccountId,
            String creditAllowedModels, String creditDeniedModels) {
        this.grantedCreditAllowedModels = creditAllowedModels;
        this.grantedCreditDeniedModels = creditDeniedModels;
        this.grantedRpm = rpm;
        this.grantedTpm = tpm;
        this.grantedConcurrency = concurrency;
        this.grantedDailyTokens = dailyTokens;
        this.grantedCreditLimit = creditLimit;
        this.grantedCreditLimitReset = creditLimitReset;
        if (grantedOpenrouterAccountId != null
                && !grantedOpenrouterAccountId.equals(openrouterAccountId)) {
            throw new IllegalStateException("granted OpenRouter account binding is immutable");
        }
        this.grantedOpenrouterAccountId = openrouterAccountId;
    }

    public Long getRequestId() {
        return requestId;
    }

    /** The stored JSON array; read it with {@link CreditModelPatterns#fromJson}. */
    public String getGrantedCreditAllowedModels() {
        return grantedCreditAllowedModels;
    }

    /** The stored JSON array; read it with {@link CreditModelPatterns#fromJson}. */
    public String getGrantedCreditDeniedModels() {
        return grantedCreditDeniedModels;
    }

    public @Nullable Integer getReqRpm() {
        return reqRpm;
    }

    public @Nullable Integer getReqTpm() {
        return reqTpm;
    }

    public @Nullable Long getReqDailyTokens() {
        return reqDailyTokens;
    }

    public @Nullable Integer getGrantedRpm() {
        return grantedRpm;
    }

    public @Nullable Integer getGrantedTpm() {
        return grantedTpm;
    }

    public @Nullable Integer getGrantedConcurrency() {
        return grantedConcurrency;
    }

    public @Nullable Long getGrantedDailyTokens() {
        return grantedDailyTokens;
    }

    public @Nullable BigDecimal getGrantedCreditLimit() {
        return grantedCreditLimit;
    }

    public @Nullable CreditLimitReset getGrantedCreditLimitReset() {
        return grantedCreditLimitReset;
    }

    public @Nullable Long getGrantedOpenrouterAccountId() { return grantedOpenrouterAccountId; }
}
