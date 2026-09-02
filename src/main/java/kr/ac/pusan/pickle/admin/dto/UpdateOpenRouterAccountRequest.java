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
    private @Nullable String program;
    private boolean programSet;

    @Size(max = 500)
    private @Nullable String contact;
    private boolean contactSet;

    private OpenRouterAccountStatus status;
    private boolean statusSet;

    @Schema(description = "새 account 이름. 생략하면 유지하며 null은 허용하지 않습니다.")
    public String getName() { return name; }
    public void setName(@Nullable String name) { this.name = name; this.nameSet = true; }
    @Schema(description = "새 사업. 생략하면 유지하고 null이면 지웁니다.")
    public @Nullable String getProgram() { return program; }
    public void setProgram(@Nullable String value) {
        program = value; programSet = true;
    }
    @Schema(description = "새 담당자. 생략하면 유지하고 null이면 지웁니다.")
    public @Nullable String getContact() { return contact; }
    public void setContact(@Nullable String value) {
        contact = value; contactSet = true;
    }
    @Schema(description = "새 lifecycle 상태. 생략하면 유지하며 null은 허용하지 않습니다.")
    public OpenRouterAccountStatus getStatus() { return status; }
    public void setStatus(@Nullable OpenRouterAccountStatus status) {
        this.status = status; statusSet = true;
    }

    @Schema(hidden = true) public boolean isNameSet() { return nameSet; }
    @Schema(hidden = true) public boolean isProgramSet() { return programSet; }
    @Schema(hidden = true) public boolean isContactSet() { return contactSet; }
    @Schema(hidden = true) public boolean isStatusSet() { return statusSet; }
    @Schema(hidden = true) public boolean hasAny() {
        return nameSet || programSet || contactSet || statusSet;
    }
}
