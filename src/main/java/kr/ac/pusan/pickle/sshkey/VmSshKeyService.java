package kr.ac.pusan.pickle.sshkey;

import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.VmAccess;
import kr.ac.pusan.pickle.access.VmAccessService;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.crypto.CredentialCipher;
import kr.ac.pusan.pickle.common.crypto.GeneratedSshKeyPair;
import kr.ac.pusan.pickle.common.crypto.ParsedSshKey;
import kr.ac.pusan.pickle.common.crypto.SshKeyPairGenerator;
import kr.ac.pusan.pickle.common.crypto.SshPublicKeyParser;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.sshkey.dto.VmSshKeyIssueResponse;
import kr.ac.pusan.pickle.sshkey.dto.VmSshKeyStatus;
import kr.ac.pusan.pickle.sshkey.dto.VmSshKeyView;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-(user, VM) SSH keypair issue, re-issue, download and deletion (V85).
 *
 * <p>The platform issues the key; users do not bring their own. What that buys
 * is scope: the key opens one VM, so a leaked private key costs one machine
 * rather than every machine its owner can reach.</p>
 *
 * <p>Every operation re-checks resource MEMBER+ live, download included. Checking
 * only at issue time would let someone who has since lost access keep pulling
 * the stored ciphertext down. Losing access does not delete the row — the
 * gateway refuses the key on the next connection, and that single choke point is
 * more trustworthy than deleting rows from the several places a grant can end.</p>
 *
 * <p>The private key never reaches a log or an audit {@code detail}; audits carry
 * the fact plus the fingerprint.</p>
 */
@Service
public class VmSshKeyService {

    private final VmSshKeyRepository repository;
    private final VmAccessService vmAccessService;
    private final SshKeyPairGenerator generator;
    private final SshPublicKeyParser parser;
    private final CredentialCipher credentialCipher;
    private final AuditService auditService;

    public VmSshKeyService(VmSshKeyRepository repository, VmAccessService vmAccessService,
            SshKeyPairGenerator generator, SshPublicKeyParser parser,
            CredentialCipher credentialCipher, AuditService auditService) {
        this.repository = repository;
        this.vmAccessService = vmAccessService;
        this.generator = generator;
        this.parser = parser;
        this.credentialCipher = credentialCipher;
        this.auditService = auditService;
    }

    /**
     * The private-key file name. The hostname is globally unique and never
     * reused, so a person with several VMs ends up with distinguishable files
     * rather than a folder of {@code id_ed25519 (3)}.
     */
    public static String fileNameFor(Vm vm) {
        return "pickle-" + vm.getHostname() + ".pem";
    }

    @Transactional(readOnly = true)
    public VmSshKeyStatus status(AuthenticatedUser actor, UUID vmId) {
        Vm vm = requireMember(actor, vmId);
        return new VmSshKeyStatus(repository.findByVmIdAndUserId(vm.getId(), actor.id())
                .map(key -> VmSshKeyView.of(key, fileNameFor(vm)))
                .orElse(null));
    }

    /** Issues this VM's key for the caller. Already issued answers 409. */
    @Transactional
    public VmSshKeyIssueResponse issue(AuthenticatedUser actor, UUID vmId, String ip) {
        Vm vm = requireMember(actor, vmId);
        requireAlive(vm);
        if (repository.findByVmIdAndUserId(vm.getId(), actor.id()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.SSH_KEY_ALREADY_ISSUED,
                    "이미 발급된 키가 있습니다",
                    "이 VM의 SSH 키는 이미 발급되어 있습니다. 개인키를 다시 내려받거나, 키를 재발급해 주세요.");
        }
        try {
            return created(actor, vm, ip, AuditService.VM_SSH_KEY_ISSUE, null);
        } catch (DataIntegrityViolationException e) {
            // Two clicks racing past the check above land on the (vm_id, user_id)
            // index. That is the same "you already have one" the check reports,
            // so it answers the same way rather than a 500.
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.SSH_KEY_ALREADY_ISSUED,
                    "이미 발급된 키가 있습니다",
                    "이 VM의 SSH 키는 이미 발급되어 있습니다. 개인키를 다시 내려받거나, 키를 재발급해 주세요.");
        }
    }

    /**
     * Replaces the key with a fresh pair, invalidating the old one immediately.
     *
     * <p>This is the user's own revocation: with the account-wide key page gone,
     * re-issue is what someone whose laptop was stolen reaches for. Delete and
     * insert happen in one transaction so there is no window without a key.</p>
     */
    @Transactional
    public VmSshKeyIssueResponse reissue(AuthenticatedUser actor, UUID vmId, String ip) {
        Vm vm = requireMember(actor, vmId);
        requireAlive(vm);
        VmSshKey previous = repository.findByVmIdAndUserId(vm.getId(), actor.id())
                .orElseThrow(VmSshKeyService::keyNotIssued);
        String previousFingerprint = previous.getFingerprintSha256();
        repository.delete(previous);
        repository.flush();
        return created(actor, vm, ip, AuditService.VM_SSH_KEY_REISSUE, previousFingerprint);
    }

    /** Returns the stored private key again, auditing every download. */
    @Transactional
    public VmSshKeyIssueResponse download(AuthenticatedUser actor, UUID vmId, String ip) {
        Vm vm = requireMember(actor, vmId);
        VmSshKey key = repository.findByVmIdAndUserId(vm.getId(), actor.id())
                .orElseThrow(VmSshKeyService::keyNotIssued);
        String fileName = fileNameFor(vm);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.VM_SSH_KEY_DOWNLOAD, "vm", vm.getPublicId(),
                Map.of("keyId", key.getPublicId().toString(),
                        "fingerprint", key.getFingerprintSha256()), ip);
        return new VmSshKeyIssueResponse(credentialCipher.decrypt(key.getPrivateKeyEnc()),
                fileName, VmSshKeyView.of(key, fileName));
    }

    @Transactional
    public void delete(AuthenticatedUser actor, UUID vmId, String ip) {
        Vm vm = requireMember(actor, vmId);
        VmSshKey key = repository.findByVmIdAndUserId(vm.getId(), actor.id())
                .orElseThrow(VmSshKeyService::keyNotIssued);
        repository.delete(key);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.VM_SSH_KEY_DELETE, "vm", vm.getPublicId(),
                Map.of("keyId", key.getPublicId().toString(),
                        "fingerprint", key.getFingerprintSha256()), ip);
    }

    private VmSshKeyIssueResponse created(AuthenticatedUser actor, Vm vm, String ip,
            String auditAction, String previousFingerprint) {
        GeneratedSshKeyPair pair = generator.generate(actor.email() + "@" + vm.getHostname());
        // The parser owns the canonical fingerprint and the comment-free
        // normalisation the gateway compares against; nothing else recomputes them.
        ParsedSshKey parsed = parser.parse(pair.publicKeyLine());
        VmSshKey saved = repository.save(new VmSshKey(vm.getId(), actor.id(),
                parsed.normalizedLine(), parsed.fingerprint(),
                credentialCipher.encrypt(pair.privateKeyPem())));

        Map<String, Object> detail = previousFingerprint == null
                ? Map.of("keyId", saved.getPublicId().toString(),
                        "fingerprint", saved.getFingerprintSha256())
                : Map.of("keyId", saved.getPublicId().toString(),
                        "fingerprint", saved.getFingerprintSha256(),
                        "previousFingerprint", previousFingerprint);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), auditAction,
                "vm", vm.getPublicId(), detail, ip);

        String fileName = fileNameFor(vm);
        return new VmSshKeyIssueResponse(pair.privateKeyPem(), fileName,
                VmSshKeyView.of(saved, fileName));
    }

    /**
     * Non-member answers 404 (the VM's existence is masked); a workspace member
     * below MEMBER on this VM gets an honest 403. Same line the SSH gateway and
     * the web terminal draw.
     */
    private Vm requireMember(AuthenticatedUser actor, UUID vmId) {
        VmAccess access = vmAccessService.of(actor, vmId);
        return access.requireAtLeast(ResourceRole.MEMBER, "SSH 키를 사용할 권한이 없습니다",
                "이 VM의 참여자(MEMBER) 이상만 SSH 키를 발급받을 수 있습니다.");
    }

    /**
     * Destroying a VM deletes its keys, and a soft-deleted row keeps its access
     * list, so without this a member could mint a fresh private key for a machine
     * that no longer exists — putting back exactly the ciphertext the destroy
     * pipeline removed. Downloading and deleting stay open so somebody can still
     * clean up what they hold.
     */
    private static void requireAlive(Vm vm) {
        if (vm.getStatus() == VmStatus.DELETED || vm.getStatus() == VmStatus.DELETING) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                    "현재 상태에서는 수행할 수 없는 작업입니다",
                    "파기된 VM에는 SSH 키를 발급할 수 없습니다.");
        }
    }

    private static ApiException keyNotIssued() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다",
                "이 VM에 발급된 SSH 키가 없습니다. 먼저 키를 발급해 주세요.");
    }
}
