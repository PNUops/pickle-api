package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountStatus;
import org.jspecify.annotations.Nullable;

/** Presence-tracked partial update; explicit null clears optional references. */
public class UpdateOpenRouterAccountRequest {

    @Size(max = 120)
    private String name;
    private boolean nameSet;

    @Size(max = 500)
    private @Nullable String fundingReference;
    private boolean fundingReferenceSet;

    @Size(max = 500)
    private @Nullable String evidenceReference;
    private boolean evidenceReferenceSet;

    private OpenRouterAccountStatus status;
    private boolean statusSet;

    @Schema(description = "새 account 이름. 생략하면 유지하며 null은 허용하지 않습니다.")
    public String getName() { return name; }
    public void setName(@Nullable String name) { this.name = name; this.nameSet = true; }
    @Schema(description = "새 재원 참조. 생략하면 유지하고 null이면 지웁니다.")
    public @Nullable String getFundingReference() { return fundingReference; }
    public void setFundingReference(@Nullable String value) {
        fundingReference = value; fundingReferenceSet = true;
    }
    @Schema(description = "새 증빙 참조. 생략하면 유지하고 null이면 지웁니다.")
    public @Nullable String getEvidenceReference() { return evidenceReference; }
    public void setEvidenceReference(@Nullable String value) {
        evidenceReference = value; evidenceReferenceSet = true;
    }
    @Schema(description = "새 lifecycle 상태. 생략하면 유지하며 null은 허용하지 않습니다.")
    public OpenRouterAccountStatus getStatus() { return status; }
    public void setStatus(@Nullable OpenRouterAccountStatus status) {
        this.status = status; statusSet = true;
    }

    @Schema(hidden = true) public boolean isNameSet() { return nameSet; }
    @Schema(hidden = true) public boolean isFundingReferenceSet() { return fundingReferenceSet; }
    @Schema(hidden = true) public boolean isEvidenceReferenceSet() { return evidenceReferenceSet; }
    @Schema(hidden = true) public boolean isStatusSet() { return statusSet; }
    @Schema(hidden = true) public boolean hasAny() {
        return nameSet || fundingReferenceSet || evidenceReferenceSet || statusSet;
    }
}
