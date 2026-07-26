package kr.ac.pusan.pickle.mfa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.common.crypto.CredentialCipher;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.mfa.dto.MfaRecoveryCodesResponse;
import kr.ac.pusan.pickle.mfa.dto.MfaSetupResponse;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 2FA(TOTP) enrollment, verification and login step-up (the MFA enrollment enforcement gate).
 *
 * <p>The active/pending secret split ({@link UserMfa}) lets a repeated
 * {@code begin} overwrite an un-activated secret without touching a live
 * enrollment. Recovery codes are BCrypt-hashed and single-use; the raw values
 * (and the Base32 secret) are shown exactly once. Login step-up tokens are
 * opaque, 5-minute, single-use — stored only as a sha256 hash.</p>
 */
@Service
public class MfaService {

    private static final int RECOVERY_CODE_COUNT = 10;
    /** Unambiguous alphabet (no 0/1/i/l/o) for human-typed recovery codes. */
    private static final String RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final int RECOVERY_GROUPS = 3;
    private static final int RECOVERY_GROUP_LEN = 4;
    private static final Duration LOGIN_CHALLENGE_TTL = Duration.ofMinutes(5);

    /** Rate-limit scopes for the password re-verification points (see {@link #guardPasswordAttempt}). */
    private static final String SCOPE_BEGIN = "mfa_begin";
    private static final String SCOPE_DISABLE = "mfa_disable";
    private static final String SCOPE_RECOVERY = "mfa_recovery";

    private final SecureRandom random = new SecureRandom();

    private final UserRepository userRepository;
    private final UserMfaRepository userMfaRepository;
    private final MfaRecoveryCodeRepository recoveryCodeRepository;
    private final MfaLoginTokenRepository loginTokenRepository;
    private final TotpService totpService;
    private final CredentialCipher credentialCipher;
    private final PasswordEncoder passwordEncoder;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public MfaService(UserRepository userRepository, UserMfaRepository userMfaRepository,
            MfaRecoveryCodeRepository recoveryCodeRepository,
            MfaLoginTokenRepository loginTokenRepository, TotpService totpService,
            CredentialCipher credentialCipher, PasswordEncoder passwordEncoder,
            RateLimitService rateLimitService,
            AuditService auditService, NotificationService notificationService) {
        this.userRepository = userRepository;
        this.userMfaRepository = userMfaRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.loginTokenRepository = loginTokenRepository;
        this.totpService = totpService;
        this.credentialCipher = credentialCipher;
        this.passwordEncoder = passwordEncoder;
        this.rateLimitService = rateLimitService;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    /** True once the account has a confirmed, active TOTP secret. */
    public boolean isEnrolled(long userId) {
        return userMfaRepository.isEnrolled(userId);
    }

    // ── enrollment (/me/mfa/*) ───────────────────────────────────────────────

    @Transactional
    public MfaSetupResponse begin(long userId, String password, String ip) {
        User user = loadUser(userId);
        guardPasswordAttempt(SCOPE_BEGIN, user.getEmail(), ip);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            rateLimitService.registerLoginFailure(user.getEmail());
            throw passwordMismatch();
        }
        rateLimitService.clearLoginFailures(user.getEmail());
        UserMfa mfa = userMfaRepository.findById(userId).orElseGet(() -> new UserMfa(userId));
        if (mfa.isEnrolled()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.MFA_ALREADY_ENROLLED,
                    "이미 2단계 인증이 설정되어 있습니다", "기존 설정을 해제한 뒤 다시 등록할 수 있습니다.");
        }
        String secret = totpService.generateSecret();
        mfa.startPending(credentialCipher.encrypt(secret), Instant.now());
        userMfaRepository.save(mfa);
        return new MfaSetupResponse(secret, totpService.otpauthUri(user.getEmail(), secret));
    }

    @Transactional
    public MfaRecoveryCodesResponse activate(long userId, String code, String ip) {
        User user = loadUser(userId);
        UserMfa mfa = userMfaRepository.findById(userId).orElse(null);
        if (mfa == null || !mfa.hasPendingSetup()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.MFA_SETUP_NOT_IN_PROGRESS,
                    "진행 중인 2단계 인증 등록이 없습니다", "등록을 처음부터 다시 시작해 주세요.");
        }
        String secret = credentialCipher.decrypt(mfa.getPendingSecretEnc());
        if (!totpService.verify(secret, code, Instant.now())) {
            throw codeInvalid("인증 앱의 최신 코드를 확인해 주세요.");
        }
        mfa.activate(Instant.now());
        userMfaRepository.save(mfa);
        List<String> codes = replaceRecoveryCodes(userId);

        auditService.record(user.getId(), user.getRole().name(), AuditService.ACCOUNT_MFA_ENROLL,
                "user", user.getId(), Map.of(), ip);
        notificationService.publish(user.getId(), NotificationEvent.ACCOUNT_MFA_ENROLLED, Map.of(),
                "account_mfa_enrolled:" + user.getId() + ":" + mfa.getEnabledAt());
        return new MfaRecoveryCodesResponse(codes);
    }

    @Transactional
    public void disable(long userId, String password, String code, String recoveryCode, String ip) {
        User user = loadUser(userId);
        guardPasswordAttempt(SCOPE_DISABLE, user.getEmail(), ip);
        rateLimitService.checkCodeLock(user.getEmail());
        UserMfa mfa = enrolledOrThrow(userId);
        requireExactlyOneCode(code, recoveryCode);
        // The password is checked first and on its own, so a wrong code never
        // burns a recovery code and never feeds the login lockout. The response
        // stays the same either way, so which factor failed is not disclosed.
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            rateLimitService.registerLoginFailure(user.getEmail());
            throw codeInvalid("비밀번호와 인증 코드를 다시 확인해 주세요.");
        }
        if (!verifyEnrolledCode(mfa, userId, code, recoveryCode)) {
            rateLimitService.registerCodeFailure(user.getEmail());
            throw codeInvalid("비밀번호와 인증 코드를 다시 확인해 주세요.");
        }
        clearFailureCounters(user.getEmail());
        userMfaRepository.deleteByUserId(userId);
        recoveryCodeRepository.deleteByUserId(userId);

        auditService.record(user.getId(), user.getRole().name(), AuditService.ACCOUNT_MFA_DISABLE,
                "user", user.getId(), Map.of(), ip);
        notificationService.publish(user.getId(), NotificationEvent.ACCOUNT_MFA_DISABLED, Map.of(),
                "account_mfa_disabled:" + user.getId() + ":" + Instant.now().toEpochMilli());
    }

    @Transactional
    public MfaRecoveryCodesResponse regenerateRecoveryCodes(long userId, String password, String code,
            String ip) {
        User user = loadUser(userId);
        guardPasswordAttempt(SCOPE_RECOVERY, user.getEmail(), ip);
        rateLimitService.checkCodeLock(user.getEmail());
        UserMfa mfa = enrolledOrThrow(userId);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            rateLimitService.registerLoginFailure(user.getEmail());
            throw codeInvalid("비밀번호와 인증 코드를 다시 확인해 주세요.");
        }
        if (!totpService.verify(activeSecret(mfa), code, Instant.now())) {
            rateLimitService.registerCodeFailure(user.getEmail());
            throw codeInvalid("비밀번호와 인증 코드를 다시 확인해 주세요.");
        }
        clearFailureCounters(user.getEmail());
        return new MfaRecoveryCodesResponse(replaceRecoveryCodes(userId));
    }

    /**
     * Guards a session-scoped password re-verification: the same dual-key sliding
     * window as login (per IP and per account) plus the shared login lockout, so a
     * hijacked session cannot brute-force the account password through the 2FA
     * management endpoints. A wrong password feeds {@code registerLoginFailure},
     * so that lockout is one counter across login and every re-verification
     * point; a wrong TOTP/recovery code is throttled on its own counter
     * ({@code registerCodeFailure}) and leaves login alone.
     */
    private void guardPasswordAttempt(String scope, String email, String ip) {
        rateLimitService.hit(scope + ":ip", ip, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        rateLimitService.hit(scope + ":acct", email, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        rateLimitService.checkLoginLock(email);
    }

    /** A fully accepted re-verification resets both failure counters. */
    private void clearFailureCounters(String email) {
        rateLimitService.clearLoginFailures(email);
        rateLimitService.clearCodeFailures(email);
    }

    // ── login step-up (/auth/login → /auth/mfa) ─────────────────────────────

    /** Issues a fresh single-use step-up token for an enrolled account; returns the raw value. */
    @Transactional
    public String issueLoginChallenge(long userId) {
        String rawToken = newRawToken();
        loginTokenRepository.save(new MfaLoginToken(userId, sha256Hex(rawToken),
                Instant.now().plus(LOGIN_CHALLENGE_TTL)));
        return rawToken;
    }

    /**
     * Loads a live step-up token or throws 410. A wrong code must NOT consume the
     * token, so consumption is a separate {@link #consumeChallenge} call the
     * caller makes only after the code verifies.
     */
    @Transactional(readOnly = true)
    public MfaLoginToken loadChallengeOrThrow(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw challengeExpired();
        }
        MfaLoginToken token = loginTokenRepository.findByTokenHash(sha256Hex(rawToken))
                .orElseThrow(MfaService::challengeExpired);
        if (token.isConsumed() || token.isExpired(Instant.now())) {
            throw challengeExpired();
        }
        return token;
    }

    @Transactional
    public void consumeChallenge(MfaLoginToken token) {
        // Conditional consume: a concurrent (or repeated) completion of the same
        // step-up token loses the UPDATE and gets 410, so tokens issue exactly one
        // session.
        if (loginTokenRepository.consume(token.getId(), Instant.now()) == 0) {
            throw challengeExpired();
        }
    }

    // ── shared code verification (login step-up + withdraw) ─────────────────

    /**
     * Verifies a TOTP code or single-use recovery code for an enrolled user,
     * consuming the recovery code on success. Lenient: returns false (no throw)
     * unless exactly one of the two is present and correct — callers that need a
     * 422 for a missing/ambiguous code call {@link #requireExactlyOneCode} first.
     */
    @Transactional
    public boolean verifyEnrolledCode(long userId, String code, String recoveryCode) {
        if (!isExactlyOne(code, recoveryCode)) {
            return false;
        }
        UserMfa mfa = userMfaRepository.findById(userId).filter(UserMfa::isEnrolled).orElse(null);
        if (mfa == null) {
            return false;
        }
        return verifyEnrolledCode(mfa, userId, code, recoveryCode);
    }

    private boolean verifyEnrolledCode(UserMfa mfa, long userId, String code, String recoveryCode) {
        if (code != null && !code.isBlank()) {
            return verifyTotpNoReplay(mfa, code);
        }
        return consumeRecoveryCode(userId, recoveryCode);
    }

    /**
     * Verifies a TOTP code against the active secret and rejects a step already
     * consumed (replay within the ~90s validity window). The matched step is
     * persisted so the same code cannot be reused, e.g. login then an immediate
     * disable with the same code — the second must wait for the next code.
     */
    private boolean verifyTotpNoReplay(UserMfa mfa, String code) {
        long step = totpService.matchingStep(activeSecret(mfa), code, Instant.now());
        if (step == TotpService.NO_MATCH) {
            return false;
        }
        Long last = mfa.getLastTotpStep();
        if (last != null && step <= last) {
            return false;
        }
        mfa.recordTotpStep(step);
        userMfaRepository.save(mfa);
        return true;
    }

    // ── admin reset ─────────────────────────────────────────────────────────

    /** SYS_ADMIN lockout recovery: drops enrollment + codes; 409 if the user is not enrolled. */
    @Transactional
    public void adminReset(long actorId, String actorRole, User target, String ip) {
        if (!userMfaRepository.isEnrolled(target.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.MFA_NOT_ENROLLED,
                    "2단계 인증이 설정되어 있지 않습니다", "해당 사용자는 2단계 인증을 사용하고 있지 않습니다.");
        }
        userMfaRepository.deleteByUserId(target.getId());
        recoveryCodeRepository.deleteByUserId(target.getId());

        auditService.record(actorId, actorRole, AuditService.ACCOUNT_MFA_RESET,
                "user", target.getId(), Map.of("targetEmail", target.getEmail()), ip);
        notificationService.publish(target.getId(), NotificationEvent.ACCOUNT_MFA_RESET, Map.of(),
                "account_mfa_reset:" + target.getId() + ":" + Instant.now().toEpochMilli());
    }

    // ── internals ────────────────────────────────────────────────────────────

    private String activeSecret(UserMfa mfa) {
        return credentialCipher.decrypt(mfa.getTotpSecretEnc());
    }

    private boolean consumeRecoveryCode(long userId, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return false;
        }
        String normalized = rawCode.trim().toLowerCase(Locale.ROOT);
        for (MfaRecoveryCode candidate : recoveryCodeRepository.findByUserIdAndUsedAtIsNull(userId)) {
            if (passwordEncoder.matches(normalized, candidate.getCodeHash())) {
                // Conditional consume: two parallel logins presenting the same
                // code — only the one that flips used_at wins.
                return recoveryCodeRepository.consume(candidate.getId(), Instant.now()) == 1;
            }
        }
        return false;
    }

    /** Deletes existing codes and mints ten fresh ones; returns the raw values (shown once). */
    private List<String> replaceRecoveryCodes(long userId) {
        recoveryCodeRepository.deleteByUserId(userId);
        List<String> raw = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code = newRecoveryCode();
            raw.add(code);
            recoveryCodeRepository.save(new MfaRecoveryCode(userId, passwordEncoder.encode(code)));
        }
        return raw;
    }

    private String newRecoveryCode() {
        StringBuilder out = new StringBuilder();
        for (int group = 0; group < RECOVERY_GROUPS; group++) {
            if (group > 0) {
                out.append('-');
            }
            for (int c = 0; c < RECOVERY_GROUP_LEN; c++) {
                out.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
            }
        }
        return out.toString();
    }

    private User loadUser(long userId) {
        return userRepository.findById(userId).orElseThrow(MfaService::sessionUserGone);
    }

    private UserMfa enrolledOrThrow(long userId) {
        return userMfaRepository.findById(userId).filter(UserMfa::isEnrolled)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, ErrorCodes.MFA_NOT_ENROLLED,
                        "2단계 인증이 설정되어 있지 않습니다", "먼저 2단계 인증을 등록해 주세요."));
    }

    private static boolean isExactlyOne(String code, String recoveryCode) {
        boolean hasCode = code != null && !code.isBlank();
        boolean hasRecovery = recoveryCode != null && !recoveryCode.isBlank();
        return hasCode != hasRecovery;
    }

    /** 422 unless exactly one of code/recoveryCode is present (login step-up / disable). */
    public static void requireExactlyOneCode(String code, String recoveryCode) {
        if (!isExactlyOne(code, recoveryCode)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.VALIDATION_FAILED,
                    "입력값이 올바르지 않습니다", "인증 코드 또는 복구 코드 중 하나만 입력해 주세요.");
        }
    }

    private String newRawToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── exception factories (some reused by AuthService for login step-up) ───

    private static ApiException passwordMismatch() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.AUTH_PASSWORD_MISMATCH,
                "본인 확인에 실패했습니다", "비밀번호를 다시 확인해 주세요.");
    }

    /** 403 for enrollment/disable flows; login step-up throws 401 via {@link #loginCodeInvalid()}. */
    private static ApiException codeInvalid(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.AUTH_MFA_CODE_INVALID,
                "인증 코드가 올바르지 않습니다", detail);
    }

    public static ApiException loginCodeInvalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_MFA_CODE_INVALID,
                "인증 코드가 올바르지 않습니다", "입력한 코드가 올바르지 않습니다. 인증 앱의 최신 코드를 확인해 주세요.");
    }

    private static ApiException challengeExpired() {
        return new ApiException(HttpStatus.GONE, ErrorCodes.AUTH_MFA_TOKEN_EXPIRED,
                "인증 세션이 만료되었습니다", "2단계 인증 시간이 지났습니다. 처음부터 다시 로그인해 주세요.");
    }

    private static ApiException sessionUserGone() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_TOKEN_INVALID,
                "인증이 필요합니다", "액세스 토큰이 없거나 만료되었습니다. 토큰을 갱신한 뒤 다시 시도해 주세요.");
    }
}
