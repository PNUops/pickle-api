package kr.ac.pusan.pickle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform SSH identity injected into every VM (M5.5, docs/plan/05). The
 * gateway authenticates upstream to guest VMs with its local platform ed25519
 * key; the matching <b>public</b> key here is pushed into each VM via cloud-init
 * {@code sshkeys} at provisioning, so the {@code student} account trusts the
 * gateway without any per-user key on the VM.
 *
 * @param platformPublicKey the gateway's platform public key in {@code
 *                          authorized_keys} one-line form (env
 *                          {@code PICKLE_SSH_PLATFORM_PUBLIC_KEY}); no default —
 *                          provisioning fails closed when unset
 */
@ConfigurationProperties(prefix = "pickle.ssh")
public record SshPlatformProperties(String platformPublicKey) {

    /** The configured key, or throws if provisioning runs without it set. */
    public String requirePlatformPublicKey() {
        if (platformPublicKey == null || platformPublicKey.isBlank()) {
            throw new IllegalStateException("pickle.ssh.platform-public-key is not set. Provide "
                    + "PICKLE_SSH_PLATFORM_PUBLIC_KEY (the SSH gateway's platform public key) so "
                    + "cloud-init can authorize the gateway on every VM.");
        }
        return platformPublicKey;
    }
}
