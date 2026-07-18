package kr.ac.pusan.pickle.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh-token persistence: opaque 256-bit tokens stored as SHA-256 hashes,
 * rotation chains via {@code rotated_from}, chain-wide revocation on reuse.
 * Mutations run in their own short transactions so revocations persist even
 * when the caller subsequently responds 401.
 */
@Service
public class RefreshTokenService {

    public record IssuedToken(String rawToken, RefreshToken entity) {
    }

    private final RefreshTokenRepository repository;

    public RefreshTokenService(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<RefreshToken> findByRawToken(String rawToken) {
        return repository.findByTokenHash(TokenHasher.sha256Hex(rawToken));
    }

    @Transactional
    public IssuedToken issue(Long userId, Duration ttl, Long rotatedFrom, String userAgent, String ip) {
        String rawToken = TokenHasher.newToken();
        RefreshToken entity = repository.save(new RefreshToken(userId, TokenHasher.sha256Hex(rawToken),
                Instant.now().plus(ttl), rotatedFrom, userAgent, ip));
        return new IssuedToken(rawToken, entity);
    }

    /**
     * Atomically revokes the old token and issues its successor. Returns empty
     * when the token was already revoked by a concurrent request (treated as
     * reuse by the caller).
     */
    @Transactional
    public Optional<IssuedToken> rotate(RefreshToken current, Duration ttl, String userAgent, String ip) {
        int revoked = repository.revokeIfActive(current.getId());
        if (revoked == 0) {
            return Optional.empty();
        }
        return Optional.of(issue(current.getUserId(), ttl, current.getId(), userAgent, ip));
    }

    @Transactional
    public void revoke(Long id) {
        repository.revokeIfActive(id);
    }

    /** Kills every active session of a user (password change / reset). */
    @Transactional
    public void revokeAllForUser(Long userId) {
        repository.revokeAllActiveByUserId(userId);
    }

    @Transactional
    public void revokeChainFrom(Long id) {
        repository.revokeChainFrom(id);
    }
}
