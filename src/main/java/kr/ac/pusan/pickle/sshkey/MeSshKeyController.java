package kr.ac.pusan.pickle.sshkey;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.RequireReauth;
import kr.ac.pusan.pickle.sshkey.dto.SshKeyCreateRequest;
import kr.ac.pusan.pickle.sshkey.dto.SshKeyGenerateRequest;
import kr.ac.pusan.pickle.sshkey.dto.SshKeyPrivateKeyResponse;
import kr.ac.pusan.pickle.sshkey.dto.SshKeyView;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code me}: /me/ssh-keys — per-user SSH key management. */
@RestController
@RequestMapping("/api/v1/me/ssh-keys")
public class MeSshKeyController {

    private final UserSshKeyService service;

    public MeSshKeyController(UserSshKeyService service) {
        this.service = service;
    }

    @GetMapping
    public List<SshKeyView> listKeys(@AuthenticationPrincipal AuthenticatedUser principal) {
        return service.list(principal);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequireReauth
    public SshKeyView registerKey(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody SshKeyCreateRequest request, HttpServletRequest httpRequest) {
        return service.register(principal, request.name(), request.publicKey(),
                clientIp(httpRequest));
    }

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequireReauth
    public SshKeyView generateKey(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody SshKeyGenerateRequest request, HttpServletRequest httpRequest) {
        return service.generate(principal, request.name(), clientIp(httpRequest));
    }

    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequireReauth
    public void deleteKey(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId, HttpServletRequest httpRequest) {
        service.delete(principal, keyId, clientIp(httpRequest));
    }

    /** Server-generated keys only; response is never cached (private key). */
    @GetMapping("/{keyId}/private-key")
    @RequireReauth
    public ResponseEntity<SshKeyPrivateKeyResponse> downloadPrivateKey(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID keyId,
            HttpServletRequest httpRequest) {
        SshKeyPrivateKeyResponse response =
                service.downloadPrivateKey(principal, keyId, clientIp(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }
}
