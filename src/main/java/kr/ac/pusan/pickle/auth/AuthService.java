package kr.ac.pusan.pickle.auth;

import java.time.Duration;
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
import kr.ac.pusan.pickle.consent.TermsService;
import kr.ac.pusan.pickle.group.PersonalGroupService;
import kr.ac.pusan.pickle.mfa.MfaLoginToken;
import kr.ac.pusan.pickle.mfa.MfaService;
import kr.ac.pusan.pickle.mail.AsyncMailDispatcher;
import kr.ac.pusan.pickle.mail.VerificationMailComposer;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Signup / verification / login / refresh-rotation / logout. */
@Service
public class AuthService {

    /**
     * Outcome of {@code POST /auth/login}: either a full token pair
     * ({@link AuthResult}) or, for an enrolled account, an MFA step-up challenge
     * ({@link MfaChallenge}) — no cookies/tokens are issued in the latter case.
     */
    public sealed interface LoginOutcome permits AuthResult, MfaChallenge {
    }

    /** Access token + user summary + raw refresh token for the cookie. */
    public record AuthResult(AuthTokenResponse body, String refreshToken) implements LoginOutcome {
    }

    /** 2FA 1단계 통과: caller must complete via {@code POST /auth/mfa} with this token. */
    public record MfaChallenge(String mfaToken) implements LoginOutcome {
    }

    /** Burned once for unknown accounts so login timing does not leak existence. */
    private static final String TIMING_EQUALIZER_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEeO7uHhZ8mCEyXbNP9qhrPQicvBSl2Fx16";

    /** Suppression window for the already-registered notice mail (1 per hour). */
    private static final String NOTICE_SCOPE = "signup_notice:acct";

    /** Password-reset link validity (contract: 30 minutes, single use). */
    private static final Duration PASSWORD_RESET_TOKEN_TTL = Duration.ofMinutes(30);

    private final UserRepository userRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final RefreshTokenService refreshTokenService;
    private final PersonalGroupService personalGroupService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final JwtService jwtService;
    private final AsyncMailDispatcher mailDispatcher;
    private final VerificationMailComposer verificationMailComposer;
    private final AuthProperties authProperties;
    private final MfaService mfaService;
    private final TermsService termsService;
    private final TransactionTemplate transactionTemplate;

    public AuthService(UserRepository userRepository,
            EmailVerificationRepository emailVerificationRepository,
            RefreshTokenService refreshTokenService,
            PersonalGroupService personalGroupService,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            RateLimitService rateLimitService,
            AuditService auditService,
            NotificationService notificationService,
            JwtService jwtService,
            AsyncMailDispatcher mailDispatcher,
            VerificationMailComposer verificationMailComposer,
            AuthProperties authProperties,
            MfaService mfaService,
            TermsService termsService,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.emailVerificationRepository = emailVerificationRepository;
        this.refreshTokenService = refreshTokenService;
        this.personalGroupService = personalGroupService;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.rateLimitService = rateLimitService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.jwtService = jwtService;
        this.mailDispatcher = mailDispatcher;
        this.verificationMailComposer = verificationMailComposer;
        this.authProperties = authProperties;
        this.mfaService = mfaService;
        this.termsService = termsService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Signup. Anti-enumeration: the response is the same 202 whether or not the
     * address is already on file, matching login (uniform 401) and password reset
     * (uniform 202). An address that already has an account (in any status,
     * including the permanently retained WITHDRAWN one) creates nothing and gets a
     * notice mail instead, so the person who actually owns the address learns of
     * the attempt while the requester learns nothing.
     *
     * <p>Not transactional as a whole: the unique-email race below has to answer
     * 202 after the failed insert, which a rollback-only outer transaction could
     * not commit. Account creation itself runs in {@link #transactionTemplate} so
     * an incomplete consent set (422) still rolls the user back and sends no mail.</p>
     */
    public MessageResponse signup(SignupRequest request, String ip) {
        String email = normalize(request.email());
        rateLimitService.hit("signup:ip", ip, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        rateLimitService.hit("signup:acct", email, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        // Every request-shape rejection has to happen before the address is
        // looked at, or the validation order becomes the enumeration oracle the
        // uniform 202 below is meant to remove.
        passwordPolicy.validate(request.password(), email);
        termsService.validateSignupConsents(request.consents());

        if (userRepository.existsByEmail(email)) {
            // Burn the same BCrypt cost the creation path pays, so the two paths
            // are not told apart by response time (cf. TIMING_EQUALIZER_HASH).
            passwordEncoder.encode(request.password());
            sendAlreadyRegisteredNotice(email);
            return signupAccepted();
        }
        try {
            transactionTemplate.executeWithoutResult(status -> createAccount(request, email, ip));
        } catch (DataIntegrityViolationException integrityViolation) {
            // Only the unique-email race answers 202; any other constraint failure
            // is a real fault and must not be dressed up as a completed signup.
            if (!userRepository.existsByEmail(email)) {
                throw integrityViolation;
            }
            sendAlreadyRegisteredNotice(email);
        }
        return signupAccepted();
    }

    private void createAccount(SignupRequest request, String email, String ip) {
        User user = userRepository.save(
                new User(email, passwordEncoder.encode(request.password()), request.name().strip()));
        // Consent completeness is validated here (422 rolls the whole tx back, so
        // no verification mail is sent for an incomplete signup).
        termsService.recordSignupConsents(user.getId(), request.consents());
        sendVerificationMail(user);
        auditService.record(user.getId(), user.getRole().name(), AuditService.AUTH_SIGNUP,
                "user", user.getId(), Map.of("email", user.getEmail()), ip);
    }

    private static MessageResponse signupAccepted() {
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
    public LoginOutcome login(LoginRequest request, String ip, String userAgent) {
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

        // Enrolled accounts stop at stage 1: return a step-up challenge, issue no
        // tokens/cookies, and keep the login-failure counter (the login is not
        // complete until POST /auth/mfa). Non-enrolled accounts complete here.
        if (mfaService.isEnrolled(user.getId())) {
            return new MfaChallenge(mfaService.issueLoginChallenge(user.getId()));
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
     * Login stage 2 ({@code POST /auth/mfa}): validates the step-up token and a
     * TOTP/recovery code, then issues the token pair exactly as {@link #login}
     * would. A wrong code keeps the token (401) and counts toward the login
     * lockout; an expired/consumed token is 410 (restart login).
     */
    @Transactional
    public AuthResult completeMfaLogin(String mfaToken, String code, String recoveryCode,
            String ip, String userAgent) {
        MfaService.requireExactlyOneCode(code, recoveryCode);
        MfaLoginToken challenge = mfaService.loadChallengeOrThrow(mfaToken);
        User user = userRepository.findById(challenge.getUserId())
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(AuthService::invalidCredentials);

        rateLimitService.checkLoginLock(user.getEmail());
        if (!mfaService.verifyEnrolledCode(user.getId(), code, recoveryCode)) {
            rateLimitService.registerLoginFailure(user.getEmail());
            auditService.record(user.getId(), user.getRole().name(), AuditService.AUTH_LOGIN_FAILED,
                    "user", user.getId(), Map.of("email", user.getEmail(), "reason", "mfa_code"), ip);
            throw MfaService.loginCodeInvalid();
        }
        mfaService.consumeChallenge(challenge);
        rateLimitService.clearLoginFailures(user.getEmail());

        var issued = refreshTokenService.issue(user.getId(), authProperties.refreshTokenTtl(), null, userAgent, ip);
        auditService.record(user.getId(), user.getRole().name(), AuditService.AUTH_LOGIN,
                "user", user.getId(), Map.of("email", user.getEmail(), "stage", "mfa"), ip);
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

    /**
     * Self-service password change ({@code PUT /me/password}): the current
     * session survives via the fresh token pair this returns, while the
     * version bump + refresh-token revocation invalidate every other session.
     */
    @Transactional
    public AuthResult changePassword(long userId, String currentPassword, String newPassword,
            String ip, String userAgent) {
        User user = userRepository.findById(userId).orElseThrow(AuthService::sessionUserGone);
        rateLimitService.hit("password_change:ip", ip, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        rateLimitService.hit("password_change:acct", user.getEmail(), RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        // Deliberately NOT gated on the login lockout: changing the password is
        // how someone answers a brute-force attempt on their account, and an
        // attacker hammering login must not be able to block that from an
        // already-valid session. The sliding window above still bounds the rate.

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            // Same counter as login and the other re-verification points, so a
            // hijacked session cannot switch endpoints to keep guessing.
            rateLimitService.registerLoginFailure(user.getEmail());
            auditService.record(user.getId(), user.getRole().name(), AuditService.ACCOUNT_PASSWORD_CHANGE,
                    "user", user.getId(), Map.of("result", "mismatch"), ip);
            throw passwordMismatch();
        }
        rateLimitService.clearLoginFailures(user.getEmail());
        passwordPolicy.validate(newPassword, user.getEmail());

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.bumpTokenVersion();
        // Other sessions: access tokens die on the version bump, refresh tokens
        // are revoked here; then issue a fresh pair for this session.
        refreshTokenService.revokeAllForUser(user.getId());
        var issued = refreshTokenService.issue(user.getId(), authProperties.refreshTokenTtl(), null, userAgent, ip);

        auditService.recordAfterCommit(user.getId(), user.getRole().name(),
                AuditService.ACCOUNT_PASSWORD_CHANGE, "user", user.getId(), Map.of(), ip);
        notificationService.publish(user.getId(), NotificationEvent.ACCOUNT_PASSWORD_CHANGED, Map.of(),
                "account_password_changed:" + user.getId() + ":" + user.getTokenVersion());
        return new AuthResult(
                new AuthTokenResponse(jwtService.createAccessToken(user), UserSummaryResponse.from(user)),
                issued.rawToken());
    }

    /**
     * Password-reset request ({@code POST /auth/password-reset}). Uniform 202:
     * a non-existent or non-ACTIVE account is a silent no-op so account
     * existence is not disclosed.
     */
    @Transactional
    public MessageResponse requestPasswordReset(String email, String ip) {
        String normalized = normalize(email);
        rateLimitService.hit("password_reset:ip", ip, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        rateLimitService.hit("password_reset:acct", normalized, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);

        userRepository.findByEmail(normalized)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .ifPresent(this::sendPasswordResetMail);
        return new MessageResponse("해당 주소가 등록되어 있다면 비밀번호 재설정 메일을 발송했습니다.");
    }

    /**
     * Password-reset confirm ({@code POST /auth/password-reset/confirm}):
     * single-use token, invalidates every session (version bump + refresh
     * revocation) and clears the login-failure lockout.
     */
    @Transactional
    public MessageResponse confirmPasswordReset(String rawToken, String newPassword, String ip) {
        rateLimitService.hit("password_reset_confirm:ip", ip, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);

        EmailVerification verification = emailVerificationRepository
                .findByTokenHashAndPurpose(TokenHasher.sha256Hex(rawToken), VerificationPurpose.PASSWORD_RESET)
                .orElseThrow(AuthService::resetTokenGone);
        Instant now = Instant.now();
        if (verification.isExpired(now)) {
            throw resetTokenGone();
        }
        User user = userRepository.findById(verification.getUserId())
                .orElseThrow(AuthService::resetTokenGone);
        passwordPolicy.validate(newPassword, user.getEmail());
        // Single-use guard: a concurrent/repeated confirm loses the UPDATE → 410.
        if (emailVerificationRepository.consume(verification.getId(), now) == 0) {
            throw resetTokenGone();
        }

        // consume() runs with clearAutomatically, detaching the entity above —
        // reload so the mutations below are tracked and flushed.
        user = userRepository.findById(verification.getUserId())
                .orElseThrow(AuthService::resetTokenGone);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.bumpTokenVersion();
        refreshTokenService.revokeAllForUser(user.getId());
        rateLimitService.clearLoginFailures(user.getEmail());

        auditService.recordAfterCommit(user.getId(), user.getRole().name(),
                AuditService.ACCOUNT_PASSWORD_RESET, "user", user.getId(), Map.of(), ip);
        notificationService.publish(user.getId(), NotificationEvent.ACCOUNT_PASSWORD_CHANGED, Map.of(),
                "account_password_reset:" + user.getId() + ":" + user.getTokenVersion());
        return new MessageResponse("비밀번호가 변경되었습니다. 새 비밀번호로 로그인해 주세요.");
    }

    private void sendVerificationMail(User user) {
        String rawToken = TokenHasher.newToken();
        emailVerificationRepository.save(new EmailVerification(user.getId(), TokenHasher.sha256Hex(rawToken),
                VerificationPurpose.SIGNUP, Instant.now().plus(authProperties.verificationTokenTtl())));
        mailDispatcher.dispatch(verificationMailComposer.compose(user.getEmail(), user.getName(), rawToken));
    }

    /**
     * Tells the owner of an already-registered address that someone tried to sign
     * up with it, and how to recover the account. Deliberately identical for every
     * account status (WITHDRAWN included) and carries no link or token — it is a
     * notice, not an action mail.
     */
    private void sendAlreadyRegisteredNotice(String email) {
        try {
            // At most one notice per address per hour: the response is uniform, so
            // signup must not become a way to flood a mailbox either. Suppression
            // is silent — the caller still gets the same 202, and just as fast,
            // because the send itself never runs on the request thread.
            rateLimitService.hitHourly(NOTICE_SCOPE, email, 1);
        } catch (ApiException suppressed) {
            return;
        }
        mailDispatcher.dispatch(verificationMailComposer.composeAlreadyRegistered(email));
    }

    private void sendPasswordResetMail(User user) {
        Instant now = Instant.now();
        // Only the last link stays valid: invalidate the user's open reset rows.
        emailVerificationRepository.invalidateOpen(user.getId(), VerificationPurpose.PASSWORD_RESET, now);
        String rawToken = TokenHasher.newToken();
        emailVerificationRepository.save(new EmailVerification(user.getId(), TokenHasher.sha256Hex(rawToken),
                VerificationPurpose.PASSWORD_RESET, now.plus(PASSWORD_RESET_TOKEN_TTL)));
        mailDispatcher.dispatch(
                verificationMailComposer.composePasswordReset(user.getEmail(), user.getName(), rawToken));
    }

    private static String normalize(String email) {
        return Texts.normalizeEmail(email);
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

    private static ApiException passwordMismatch() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.AUTH_PASSWORD_MISMATCH,
                "현재 비밀번호가 올바르지 않습니다", "현재 비밀번호를 다시 확인해 주세요.");
    }

    private static ApiException resetTokenGone() {
        return new ApiException(HttpStatus.GONE, ErrorCodes.AUTH_RESET_TOKEN_EXPIRED,
                "재설정 링크가 만료되었습니다",
                "재설정 링크가 만료되었거나 이미 사용되었습니다. 재설정을 다시 요청해 주세요.");
    }

    private static ApiException sessionUserGone() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_TOKEN_INVALID,
                "인증이 필요합니다", "액세스 토큰이 없거나 만료되었습니다. 토큰을 갱신한 뒤 다시 시도해 주세요.");
    }
}
