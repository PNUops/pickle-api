package kr.ac.pusan.pickle.sshkey;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.crypto.CredentialCipher;
import kr.ac.pusan.pickle.common.crypto.GeneratedSshKeyPair;
import kr.ac.pusan.pickle.common.crypto.ParsedSshKey;
import kr.ac.pusan.pickle.common.crypto.SshKeyPairGenerator;
import kr.ac.pusan.pickle.common.crypto.SshPublicKeyParseException;
import kr.ac.pusan.pickle.common.crypto.SshPublicKeyParser;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.sshkey.dto.SshKeyPrivateKeyResponse;
import kr.ac.pusan.pickle.sshkey.dto.SshKeyView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user SSH key management (contract tag {@code me}, V28). Keys are the SSH
 * gateway's primary identity: registration computes the canonical fingerprint
 * (unique platform-wide) and enforces a per-user cap; generation additionally
 * stores the private PEM as a reversible ciphertext so it can be re-downloaded.
 *
 * <p>The private key never reaches a log or an audit {@code detail} — audits
 * carry only the fact plus non-secret metadata (key id, fingerprint).</p>
 */
@Service
public class UserSshKeyService {

    /** Per-user key cap (contract: 409 SSH_KEY_LIMIT_EXCEEDED beyond this). */
    static final int MAX_KEYS_PER_USER = 10;
    private static final String PRIVATE_KEY_FILENAME = "id_ed25519_pickle";

    private final UserSshKeyRepository repository;
    private final SshPublicKeyParser parser;
    private final SshKeyPairGenerator generator;
    private final CredentialCipher credentialCipher;
    private final AuditService auditService;

    public UserSshKeyService(UserSshKeyRepository repository, SshPublicKeyParser parser,
            SshKeyPairGenerator generator, CredentialCipher credentialCipher,
            AuditService auditService) {
        this.repository = repository;
        this.parser = parser;
        this.generator = generator;
        this.credentialCipher = credentialCipher;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<SshKeyView> list(AuthenticatedUser actor) {
        return repository.findByUserIdOrderByCreatedAtAscIdAsc(actor.id()).stream()
                .map(SshKeyView::from)
                .toList();
    }

    /** Registers a pasted public key. */
    @Transactional
    public SshKeyView register(AuthenticatedUser actor, String name, String publicKey, String ip) {
        ParsedSshKey parsed = parse(publicKey);
        UserSshKey saved = persist(actor, name.strip(), parsed, null, ip,
                AuditService.USER_SSH_KEY_ADD);
        return SshKeyView.from(saved);
    }

    /** Server-generates an ed25519 key, storing the private PEM as ciphertext. */
    @Transactional
    public SshKeyView generate(AuthenticatedUser actor, String name, String ip) {
        enforceLimit(actor);
        GeneratedSshKeyPair pair = generator.generate(actor.email());
        ParsedSshKey parsed = parse(pair.publicKeyLine());
        String privateKeyEnc = credentialCipher.encrypt(pair.privateKeyPem());
        UserSshKey saved = persist(actor, name.strip(), parsed, privateKeyEnc, ip,
                AuditService.USER_SSH_KEY_GENERATE);
        return SshKeyView.from(saved);
    }

    /**
     * Returns the private PEM of a server-generated key, auditing every
     * download. A pasted key (no stored private key) or another user's key both
     * answer 404 (existence masking).
     */
    @Transactional
    public SshKeyPrivateKeyResponse downloadPrivateKey(AuthenticatedUser actor, UUID keyId,
            String ip) {
        UserSshKey key = repository.findByPublicId(keyId)
                .filter(row -> actor.id().equals(row.getUserId()))
                .orElseThrow(UserSshKeyService::keyNotFound);
        if (key.getPrivateKeyEnc() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "리소스를 찾을 수 없습니다",
                    "다운로드할 개인키가 없습니다. 직접 등록한 키의 개인키는 서버에 보관되지 않습니다.");
        }
        String privateKey = credentialCipher.decrypt(key.getPrivateKeyEnc());
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.USER_SSH_KEY_DOWNLOAD, "ssh_key", key.getPublicId(),
                Map.of("fingerprint", key.getFingerprintSha256()), ip);
        return new SshKeyPrivateKeyResponse(privateKey, PRIVATE_KEY_FILENAME);
    }

    @Transactional
    public void delete(AuthenticatedUser actor, UUID keyId, String ip) {
        UserSshKey key = repository.findByPublicId(keyId)
                .filter(row -> actor.id().equals(row.getUserId()))
                .orElseThrow(UserSshKeyService::keyNotFound);
        repository.delete(key);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.USER_SSH_KEY_DELETE, "ssh_key", key.getPublicId(),
                Map.of("fingerprint", key.getFingerprintSha256()), ip);
    }

    private ParsedSshKey parse(String publicKey) {
        try {
            return parser.parse(publicKey);
        } catch (SshPublicKeyParseException e) {
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("publicKey", e.getMessage())));
        }
    }

    private UserSshKey persist(AuthenticatedUser actor, String name, ParsedSshKey parsed,
            String privateKeyEnc, String ip, String auditAction) {
        // Duplicate fingerprint first (a specific "this key exists" signal),
        // then the per-user cap. The owner behind an existing fingerprint is
        // never disclosed — the message is deliberately owner-agnostic.
        if (repository.findByFingerprintSha256(parsed.fingerprint()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.SSH_KEY_DUPLICATE,
                    "이미 등록된 키입니다", "이미 등록된 키입니다. 다른 키를 사용해 주세요.");
        }
        enforceLimit(actor);
        UserSshKey saved = repository.save(new UserSshKey(actor.id(), name,
                parsed.algorithm().wireType(), parsed.normalizedLine(), parsed.fingerprint(),
                privateKeyEnc));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), auditAction,
                "ssh_key", saved.getPublicId(),
                Map.of("fingerprint", saved.getFingerprintSha256(),
                        "algorithm", saved.getAlgorithm()), ip);
        return saved;
    }

    private void enforceLimit(AuthenticatedUser actor) {
        if (repository.countByUserId(actor.id()) >= MAX_KEYS_PER_USER) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.SSH_KEY_LIMIT_EXCEEDED,
                    "키를 더 등록할 수 없습니다",
                    "SSH 키는 사용자당 최대 " + MAX_KEYS_PER_USER
                            + "개까지 등록할 수 있습니다. 사용하지 않는 키를 삭제해 주세요.");
        }
    }

    private static ApiException keyNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 SSH 키가 존재하지 않습니다.");
    }
}
