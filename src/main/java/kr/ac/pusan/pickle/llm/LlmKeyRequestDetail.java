package kr.ac.pusan.pickle.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    @Column(name = "req_purpose")
    private @Nullable String reqPurpose;

    @Column(name = "req_rpm")
    private @Nullable Integer reqRpm;

    @Column(name = "req_tpm")
    private @Nullable Integer reqTpm;

    @Column(name = "req_daily_tokens")
    private @Nullable Long reqDailyTokens;

    @Column(name = "granted_rpm")
    private @Nullable Integer grantedRpm;

    @Column(name = "granted_tpm")
    private @Nullable Integer grantedTpm;

    @Column(name = "granted_concurrency")
    private @Nullable Integer grantedConcurrency;

    @Column(name = "granted_daily_tokens")
    private @Nullable Long grantedDailyTokens;

    protected LlmKeyRequestDetail() {
    }

    public LlmKeyRequestDetail(long requestId, @Nullable String reqPurpose, @Nullable Integer reqRpm,
            @Nullable Integer reqTpm, @Nullable Long reqDailyTokens) {
        this.requestId = requestId;
        this.reqPurpose = reqPurpose;
        this.reqRpm = reqRpm;
        this.reqTpm = reqTpm;
        this.reqDailyTokens = reqDailyTokens;
    }

    /** Records what the reviewer granted. Nulls mean the service defaults. */
    public void grant(@Nullable Integer rpm, @Nullable Integer tpm, @Nullable Integer concurrency,
            @Nullable Long dailyTokens) {
        this.grantedRpm = rpm;
        this.grantedTpm = tpm;
        this.grantedConcurrency = concurrency;
        this.grantedDailyTokens = dailyTokens;
    }

    public Long getRequestId() {
        return requestId;
    }

    public @Nullable String getReqPurpose() {
        return reqPurpose;
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
}
