package kr.ac.pusan.pickle.identity;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.RequireReauth;
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
 * <p>Under {@code /me} rather than {@code /auth} for two reasons. {@code
 * /auth/**} is permitAll, so anything authenticated there needs a second entry
 * in the public-endpoint carve-out list, which is kept deliberately short. More
 * importantly the console's fetch wrapper treats every {@code /api/v1/auth/*}
 * path as an auth endpoint and skips attaching the reauth token to it — an
 * unlink placed there could never be sudo-gated, however it were annotated
 * here.
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
    @RequireReauth
    public void unlinkIdentity(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable IdentityProvider provider, HttpServletRequest httpRequest) {
        identityService.unlink(principal, provider, clientIp(httpRequest));
    }
}
