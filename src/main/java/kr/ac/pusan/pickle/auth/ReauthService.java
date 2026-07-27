package kr.ac.pusan.pickle.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.dto.ReverifyResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sudo-mode reauthentication (contract v0.24.0): the real session lifetime is
 * the 14-day refresh cookie, so {@code @RequireReauth} endpoints demand a
 * fresh password proof — {@code POST /auth/reverify} issues a multi-use
 * 10-minute token the console replays as {@code X-Reauth-Token}. Lives in the
 * auth package for {@link TokenHasher} (deliberately package-private — no
 * second hashing implementation). Failed attempts share the login lockout
 * counters, mirroring the body-embedded password checks (withdraw, password
 * change, MFA ops) that continue to guard credential changes themselves.
 */
@Service
public class ReauthService {

    static final Duration TTL = Duration.ofMinutes(10);

    private final AuthReverificationRepository repository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;

    public ReauthService(AuthReverificationRepository repository, UserRepository userRepository,
            PasswordEncoder passwordEncoder, RateLimitService rateLimitService,
            AuditService auditService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.rateLimitService = rateLimitService;
        this.auditService = auditService;
    }

    @Transactional
    public ReverifyResponse issue(AuthenticatedUser actor, String password, String ip) {
        User user = userRepository.findById(actor.id())
                .orElseThrow(ReauthService::passwordMismatch);
        rateLimitService.hit("reverify:ip", ip, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        rateLimitService.hit("reverify:acct", user.getEmail(), RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        rateLimitService.checkLoginLock(user.getEmail());
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            rateLimitService.registerLoginFailure(user.getEmail());
            auditService.record(actor.id(), actor.role().name(), AuditService.AUTH_REVERIFY,
                    "user", user.getId(), Map.of("result", "mismatch"), ip);
            throw passwordMismatch();
        }
        rateLimitService.clearLoginFailures(user.getEmail());
        String rawToken = TokenHasher.newToken();
        Instant expiresAt = Instant.now().plus(TTL);
        repository.save(new AuthReverification(user.getId(), TokenHasher.sha256Hex(rawToken),
                user.getTokenVersion(), expiresAt, ip));
        auditService.record(actor.id(), actor.role().name(), AuditService.AUTH_REVERIFY,
                "user", user.getId(), Map.of("result", "success"), ip);
        return new ReverifyResponse(rawToken, expiresAt);
    }

    /**
     * True when the raw token belongs to {@code userId} (cross-user replay is
     * never valid), is unexpired, and was issued under the user's CURRENT
     * token_version.
     */
    @Transactional(readOnly = true)
    public boolean isValid(long userId, String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        return repository.findByTokenHash(TokenHasher.sha256Hex(rawToken))
                .filter(token -> token.getUserId() == userId)
                .filter(token -> token.getExpiresAt().isAfter(Instant.now()))
                .filter(token -> userRepository.findById(userId)
                        .map(user -> user.getTokenVersion() == token.getTokenVersion())
                        .orElse(false))
                .isPresent();
    }

    private static ApiException passwordMismatch() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.AUTH_PASSWORD_MISMATCH,
                "현재 비밀번호가 올바르지 않습니다", "현재 비밀번호를 다시 확인해 주세요.");
    }
}
