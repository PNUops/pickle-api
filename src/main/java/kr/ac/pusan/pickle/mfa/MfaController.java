package kr.ac.pusan.pickle.mfa;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.mfa.dto.ActivateMfaRequest;
import kr.ac.pusan.pickle.mfa.dto.BeginMfaRequest;
import kr.ac.pusan.pickle.mfa.dto.DisableMfaRequest;
import kr.ac.pusan.pickle.mfa.dto.MfaRecoveryCodesResponse;
import kr.ac.pusan.pickle.mfa.dto.MfaSetupResponse;
import kr.ac.pusan.pickle.mfa.dto.RegenerateRecoveryCodesRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code me}: self-service 2FA(TOTP) enrollment. */
@RestController
@RequestMapping("/api/v1/me/mfa")
public class MfaController {

    private final MfaService mfaService;

    public MfaController(MfaService mfaService) {
        this.mfaService = mfaService;
    }

    @PostMapping("/totp")
    public MfaSetupResponse begin(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody BeginMfaRequest request, HttpServletRequest httpRequest) {
        return mfaService.begin(principal.id(), request.password(), clientIp(httpRequest));
    }

    @PostMapping("/totp/activate")
    public MfaRecoveryCodesResponse activate(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ActivateMfaRequest request, HttpServletRequest httpRequest) {
        return mfaService.activate(principal.id(), request.code(), clientIp(httpRequest));
    }

    @PostMapping("/disable")
    public MessageResponse disable(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody DisableMfaRequest request, HttpServletRequest httpRequest) {
        mfaService.disable(principal.id(), request.password(), request.code(), request.recoveryCode(),
                clientIp(httpRequest));
        return new MessageResponse("2단계 인증이 해제되었습니다.");
    }

    @PostMapping("/recovery-codes")
    public MfaRecoveryCodesResponse regenerateRecoveryCodes(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody RegenerateRecoveryCodesRequest request,
            HttpServletRequest httpRequest) {
        return mfaService.regenerateRecoveryCodes(principal.id(), request.password(), request.code(),
                clientIp(httpRequest));
    }
}
