package kr.ac.pusan.pickle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform SSH identities injected into every VM. Two
 * <b>independent</b> platform keys are authorized on the guest admin
 * account via cloud-init {@code sshkeys}:
 *
 * <ul>
 *   <li>{@code platformPublicKey} — the SSH gateway's upstream key, so the
 *       gateway can pipe publickey SSH sessions without a per-user key on the VM.
 *       Required — provisioning fails closed when unset.</li>
 *   <li>{@code terminalPublicKey} — the web-terminal bridge's key (LXC 102,
 *       env {@code PICKLE_TERMINAL_PUBLIC_KEY}). Kept separate from the gateway key
 *       for independent revocation. <b>Optional</b>: when
 *       unset only the gateway key is injected (a warning is logged) — dev VMs are
 *       re-provisioned once the bridge key is set.</li>
 * </ul>
 *
 * @param platformPublicKey the gateway's platform public key ({@code authorized_keys}
 *                          one-line form, env {@code PICKLE_SSH_PLATFORM_PUBLIC_KEY})
 * @param terminalPublicKey the terminal bridge's public key ({@code authorized_keys}
 *                          one-line form, env {@code PICKLE_TERMINAL_PUBLIC_KEY}); may be blank
 */
@ConfigurationProperties(prefix = "pickle.ssh")
public record SshPlatformProperties(String platformPublicKey, String terminalPublicKey) {

    /** The configured gateway key, or throws if provisioning runs without it set. */
    public String requirePlatformPublicKey() {
        if (platformPublicKey == null || platformPublicKey.isBlank()) {
            throw new IllegalStateException("pickle.ssh.platform-public-key is not set. Provide "
                    + "PICKLE_SSH_PLATFORM_PUBLIC_KEY (the SSH gateway's platform public key) so "
                    + "cloud-init can authorize the gateway on every VM.");
        }
        return platformPublicKey;
    }

    /** True when the terminal bridge key is configured. */
    public boolean hasTerminalPublicKey() {
        return terminalPublicKey != null && !terminalPublicKey.isBlank();
    }
}
