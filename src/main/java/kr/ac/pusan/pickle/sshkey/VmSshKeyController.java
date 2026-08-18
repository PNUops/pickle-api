package kr.ac.pusan.pickle.sshkey;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.RequireReauth;
import kr.ac.pusan.pickle.sshkey.dto.VmSshKeyIssueResponse;
import kr.ac.pusan.pickle.sshkey.dto.VmSshKeyStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's SSH key for one VM (contract tag {@code vms}).
 *
 * <p>Resource MEMBER+ throughout, with a non-member getting 404. Everything that
 * hands the private key over is reauthenticated, as the account-wide key
 * operations were.</p>
 */
@RestController
@RequestMapping("/api/v1/vms/{vmId}/ssh-key")
public class VmSshKeyController {

    private final VmSshKeyService service;

    public VmSshKeyController(VmSshKeyService service) {
        this.service = service;
    }

    /** Whether a key is issued to the caller for this VM. */
    @GetMapping
    public VmSshKeyStatus getVmSshKey(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID vmId) {
        return service.status(principal, vmId);
    }

    /** Issues the key and returns the private half once; never cached. */
    @PostMapping
    @RequireReauth
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<VmSshKeyIssueResponse> issueVmSshKey(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID vmId,
            HttpServletRequest httpRequest) {
        VmSshKeyIssueResponse response = service.issue(principal, vmId, clientIp(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    /** Replaces the key, invalidating the previous one immediately; never cached. */
    @PostMapping("/reissue")
    @RequireReauth
    public ResponseEntity<VmSshKeyIssueResponse> reissueVmSshKey(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID vmId,
            HttpServletRequest httpRequest) {
        VmSshKeyIssueResponse response = service.reissue(principal, vmId, clientIp(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    /** Re-downloads the stored private key (every download audited); never cached. */
    @GetMapping("/private-key")
    @RequireReauth
    public ResponseEntity<VmSshKeyIssueResponse> downloadVmSshKey(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID vmId,
            HttpServletRequest httpRequest) {
        VmSshKeyIssueResponse response = service.download(principal, vmId, clientIp(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    @DeleteMapping
    @RequireReauth
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVmSshKey(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID vmId, HttpServletRequest httpRequest) {
        service.delete(principal, vmId, clientIp(httpRequest));
    }
}
