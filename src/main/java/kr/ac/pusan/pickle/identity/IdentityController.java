package kr.ac.pusan.pickle.identity;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code me}: the account holder's own linked identities.
 *
 * <p>Under {@code /me} rather than {@code /auth} because {@code /auth/**} is
 * permitAll: an authenticated operation placed there would need a carve-out
 * from the public rule, and this one is authenticated.
 */
@RestController
@RequestMapping("/api/v1/me/identities")
public class IdentityController {

    private final IdentityService identityService;

    public IdentityController(IdentityService identityService) {
        this.identityService = identityService;
    }

    /**
     * Removes an external login from the account. Sudo-gated: this is the
     * removal of a way in, and the mirror of adding one — a hijacked session
     * that could quietly unlink the real owner's provider would lock them out.
     */
    @DeleteMapping("/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkIdentity(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable IdentityProvider provider, HttpServletRequest httpRequest) {
        identityService.unlink(principal, provider, clientIp(httpRequest));
    }
}
