package kr.ac.pusan.pickle.auth;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.dto.AuthTokenResponse;
import kr.ac.pusan.pickle.auth.dto.LoginRequest;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.auth.dto.SignupRequest;
import kr.ac.pusan.pickle.auth.dto.UserSummaryResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.config.AuthProperties;
import kr.ac.pusan.pickle.group.PersonalGroupService;
import kr.ac.pusan.pickle.mail.MailSender;
import kr.ac.pusan.pickle.mail.VerificationMailComposer;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Signup / verification / login / refresh-rotation / logout (docs/plan/07). */
@Service
public class AuthService {

    /** Access token + user summary + raw refresh token for the cookie. */
    public record AuthResult(AuthTokenResponse body, String refreshToken) {
    }

    /** Burned once for unknown accounts so login timing does not leak existence. */
    private static final String TIMING_EQUALIZER_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEeO7uHhZ8mCEyXbNP9qhrPQicvBSl2Fx16";

    private final UserRepository userRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final RefreshTokenService refreshTokenService;
    private final PersonalGroupService personalGroupService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;
    private final JwtService jwtService;
    private final MailSender mailSender;
    private final VerificationMailComposer verificationMailComposer;
    private final AuthProperties authProperties;

    public AuthService(UserRepository userRepository,
            EmailVerificationRepository emailVerificationRepository,
            RefreshTokenService refreshTokenService,
            PersonalGroupService personalGroupService,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            RateLimitService rateLimitService,
            AuditService auditService,
            JwtService jwtService,
            MailSender mailSender,
            VerificationMailComposer verificationMailComposer,
            AuthProperties authProperties) {
        this.userRepository = userRepository;
        this.emailVerificationRepository = emailVerificationRepository;
        this.refreshTokenService = refreshTokenService;
        this.personalGroupService = personalGroupService;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.rateLimitService = rateLimitService;
        this.auditService = auditService;
        this.jwtService = jwtService;
        this.mailSender = mailSender;
        this.verificationMailComposer = verificationMailComposer;
        this.authProperties = authProperties;
    }

    @Transactional
    public MessageResponse signup(SignupRequest request, String ip) {
        String email = normalize(request.email());
        rateLimitService.hit("signup:ip", ip, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        rateLimitService.hit("signup:acct", email, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        passwordPolicy.validate(request.password(), email);

        if (userRepository.existsByEmail(email)) {
            throw emailAlreadyRegistered();
        }

        User user;
        try {
            user = userRepository.save(
                    new User(email, passwordEncoder.encode(request.password()), request.name().strip()));
        } catch (DataIntegrityViolationException raceWithConcurrentSignup) {
            // Concurrent signup lost the unique-email race → same 409 as above.
            throw emailAlreadyRegistered();
        }
        sendVerificationMail(user);
        auditService.record(user.getId(), user.getRole().name(), AuditService.AUTH_SIGNUP,
                "user", user.getId(), Map.of("email", user.getEmail()), ip);
        return new MessageResponse("인증 메일을 발송했습니다. 메일함을 확인해 주세요.");
    }

    @Transactional
    public MessageResponse verifyEmail(String rawToken, String ip) {
        rateLimitService.hit("verify:ip", ip, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);

        EmailVerification verification = emailVerificationRepository
                .findByTokenHashAndPurpose(TokenHasher.sha256Hex(rawToken), VerificationPurpose.SIGNUP)
                .orElseThrow(AuthService::verificationTokenGone);
        Instant now = Instant.now();
        if (verification.isExpired(now)) {
            throw verificationTokenGone();
        }
        // Conditional consume: a concurrent (or repeated) use of the same
        // token loses the UPDATE and gets 410, so activation runs only once.
        if (emailVerificationRepository.consume(verification.getId(), now) == 0) {
            throw verificationTokenGone();
        }

        User user = userRepository.findById(verification.getUserId())
                .orElseThrow(AuthService::verificationTokenGone);
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(now);
        }
        personalGroupService.ensurePersonalGroup(user);
        auditService.record(user.getId(), user.getRole().name(), AuditService.AUTH_VERIFY,
                "user", user.getId(), Map.of("email", user.getEmail()), ip);
        return new MessageResponse("이메일 인증이 완료되었습니다. 이제 로그인할 수 있습니다.");
    }

    @Transactional
    public MessageResponse resendVerification(String email, String ip) {
        String normalized = normalize(email);
        rateLimitService.hit("resend:ip", ip, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        rateLimitService.hit("resend:acct", normalized, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);

        // Anti-enumeration: identical 202 whether or not the account exists.
        userRepository.findByEmail(normalized)
                .filter(user -> user.getStatus() == UserStatus.PENDING_VERIFICATION)
                .ifPresent(this::sendVerificationMail);
        return new MessageResponse("해당 주소가 등록되어 있다면 인증 메일을 다시 발송했습니다.");
    }

    @Transactional
    public AuthResult login(LoginRequest request, String ip, String userAgent) {
        String email = normalize(request.email());
        rateLimitService.hit("login:ip", ip, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        rateLimitService.hit("login:acct", email, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        rateLimitService.checkLoginLock(email);

        Optional<User> found = userRepository.findByEmail(email);
        String passwordHash = found.map(User::getPasswordHash).orElse(TIMING_EQUALIZER_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.password(), passwordHash);

        if (found.isEmpty() || !passwordMatches) {
            rateLimitService.registerLoginFailure(email);
            auditService.record(found.map(User::getId).orElse(null),
                    found.map(u -> u.getRole().name()).orElse(null), AuditService.AUTH_LOGIN_FAILED,
                    "user", found.map(User::getId).orElse(null),
                    Map.of("email", email, "reason", "bad_credentials"), ip);
            throw invalidCredentials();
        }

        User user = found.get();
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            auditService.record(user.getId(), user.getRole().name(), AuditService.AUTH_LOGIN_FAILED,
                    "user", user.getId(), Map.of("email", email, "reason", "email_not_verified"), ip);
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.AUTH_EMAIL_NOT_VERIFIED,
                    "이메일 인증이 필요합니다", "가입 시 발송된 인증 메일을 확인한 뒤 다시 로그인해 주세요.");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            // Uniform 401: do not disclose DISABLED/WITHDRAWN state.
            auditService.record(user.getId(), user.getRole().name(), AuditService.AUTH_LOGIN_FAILED,
                    "user", user.getId(),
                    Map.of("email", email, "reason", "status_" + user.getStatus().name().toLowerCase(Locale.ROOT)),
                    ip);
            throw invalidCredentials();
        }

        rateLimitService.clearLoginFailures(email);
        var issued = refreshTokenService.issue(user.getId(), authProperties.refreshTokenTtl(), null, userAgent, ip);
        auditService.record(user.getId(), user.getRole().name(), AuditService.AUTH_LOGIN,
                "user", user.getId(), Map.of("email", email), ip);
        return new AuthResult(
                new AuthTokenResponse(jwtService.createAccessToken(user), UserSummaryResponse.from(user)),
                issued.rawToken());
    }

    /**
     * Refresh-token rotation. Not transactional as a whole: revocations must
     * commit (via {@link RefreshTokenService}) before the 401 is thrown.
     */
    public AuthResult refresh(String rawToken, String ip, String userAgent) {
        if (rawToken == null || rawToken.isBlank()) {
            throw refreshTokenInvalid();
        }
        RefreshToken current = refreshTokenService.findByRawToken(rawToken)
                .orElseThrow(AuthService::refreshTokenInvalid);

        if (current.isRevoked()) {
            // Theft signal: reuse of a rotated token revokes the whole chain.
            refreshTokenService.revokeChainFrom(current.getId());
            auditService.record(current.getUserId(), null, AuditService.AUTH_REFRESH_REUSE_DETECTED,
                    "refresh_token", current.getId(), Map.of(), ip);
            throw refreshTokenInvalid();
        }
        if (current.isExpired(Instant.now())) {
            refreshTokenService.revoke(current.getId());
            throw refreshTokenInvalid();
        }

        User user = userRepository.findById(current.getUserId())
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> {
                    refreshTokenService.revokeChainFrom(current.getId());
                    return refreshTokenInvalid();
                });

        var rotated = refreshTokenService
                .rotate(current, authProperties.refreshTokenTtl(), userAgent, ip)
                .orElseThrow(() -> {
                    // Lost a race with another rotation of the same token: reuse.
                    refreshTokenService.revokeChainFrom(current.getId());
                    auditService.record(current.getUserId(), null, AuditService.AUTH_REFRESH_REUSE_DETECTED,
                            "refresh_token", current.getId(), Map.of(), ip);
                    return refreshTokenInvalid();
                });
        return new AuthResult(
                new AuthTokenResponse(jwtService.createAccessToken(user), UserSummaryResponse.from(user)),
                rotated.rawToken());
    }

    /** Idempotent: 204 whether or not a valid cookie was presented. */
    public void logout(String rawToken, String ip) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokenService.findByRawToken(rawToken).ifPresent(token -> {
            refreshTokenService.revoke(token.getId());
            auditService.record(token.getUserId(), null, AuditService.AUTH_LOGOUT,
                    "refresh_token", token.getId(), Map.of(), ip);
        });
    }

    private void sendVerificationMail(User user) {
        String rawToken = TokenHasher.newToken();
        emailVerificationRepository.save(new EmailVerification(user.getId(), TokenHasher.sha256Hex(rawToken),
                VerificationPurpose.SIGNUP, Instant.now().plus(authProperties.verificationTokenTtl())));
        mailSender.send(verificationMailComposer.compose(user.getEmail(), user.getName(), rawToken));
    }

    private static String normalize(String email) {
        return Texts.normalizeEmail(email);
    }

    private static ApiException emailAlreadyRegistered() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.AUTH_EMAIL_ALREADY_REGISTERED,
                "이미 가입된 이메일입니다", "해당 이메일로 가입된 계정이 이미 존재합니다.");
    }

    private static ApiException verificationTokenGone() {
        return new ApiException(HttpStatus.GONE, ErrorCodes.AUTH_VERIFICATION_TOKEN_EXPIRED,
                "인증 토큰이 만료되었습니다", "인증 링크가 만료되었거나 이미 사용되었습니다. 인증 메일을 다시 요청해 주세요.");
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_INVALID_CREDENTIALS,
                "로그인에 실패했습니다", "이메일 또는 비밀번호가 올바르지 않습니다.");
    }

    private static ApiException refreshTokenInvalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_REFRESH_TOKEN_INVALID,
                "세션이 만료되었습니다", "다시 로그인해 주세요.");
    }
}
