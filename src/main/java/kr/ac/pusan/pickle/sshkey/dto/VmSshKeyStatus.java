package kr.ac.pusan.pickle.sshkey.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

/**
 * Whether this VM has a key issued to the caller.
 *
 * <p>A one-field envelope rather than a bare 404, because 404 already means "no
 * such VM, as far as you are concerned" on these paths. Collapsing the two would
 * make "I have no key yet" indistinguishable from "this VM is masked from you".</p>
 */
@Schema(description = "이 VM에 대한 내 SSH 키 발급 여부")
public record VmSshKeyStatus(
        @Schema(description = "발급된 키. 아직 발급받지 않았으면 null")
        @Nullable VmSshKeyView key) {
}
