package kr.ac.pusan.pickle.oauth;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.AuthService;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.auth.ReauthService;
import kr.ac.pusan.pickle.auth.TokenHasher;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.consent.TermsService;
import kr.ac.pusan.pickle.identity.IdentityProvider;
import kr.ac.pusan.pickle.identity.UserIdentity;
import kr.ac.pusan.pickle.identity.UserIdentityRepository;
import kr.ac.pusan.pickle.mfa.MfaService;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.oauth.dto.OauthCallbackRequest;
import kr.ac.pusan.pickle.oauth.dto.OauthCompleteRequest;
import kr.ac.pusan.pickle.oauth.dto.OauthLinkedResponse;
import kr.ac.pusan.pickle.oauth.dto.OauthRegistrationResponse;
import kr.ac.pusan.pickle.oauth.dto.OauthStartRequest;
import kr.ac.pusan.pickle.oauth.dto.OauthStartResponse;
import kr.ac.pusan.pickle.profile.ProfileValidator;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Google sign-in: start, callback, and the onboarding completion that a
 * first-time account needs.
 *
 * <h2>Why the enumeration rules are different here</h2>
 * Signup answers a uniform 202 because an anonymous caller chooses the address
 * and could otherwise walk a list. On this path the address is not chosen by the
 * caller — Google asserts it after authenticating the holder — so telling that
 * holder about their own account's state is not a disclosure. It is also the
 * only way a disabled user can be told why a successful Google login did not
 * let them in.
 *
 * <p>Two things still hold, and both are load-bearing: {@link #start} accepts no
 * address at all, and password login keeps its uniform 401 for an account with
 * no password. Relaxing here and forgetting there would put the oracle back.
 */
@Service
public class GoogleOauthService {

    private static final Duration FLOW_TTL = Duration.ofMinutes(10);
    private static final Duration REGISTRATION_TTL = Duration.ofMinutes(15);

    private final GoogleOauthProperties properties;
    private final GoogleOidcClient client;
    private final OauthFlowRepository flowRepository;
    private final OauthRegistrationRepository registrationRepository;
    private final UserIdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final ReauthService reauthService;
    private final MfaService mfaService;
    private final TermsService termsService;
    private final ProfileValidator profileValidator;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public GoogleOauthService(GoogleOauthProperties properties, GoogleOidcClient client,
            OauthFlowRepository flowRepository, OauthRegistrationRepository registrationRepository,
            UserIdentityRepository identityRepository, UserRepository userRepository,
            AuthService authService, ReauthService reauthService, MfaService mfaService,
            TermsService termsService, ProfileValidator profileValidator,
            RateLimitService rateLimitService, AuditService auditService,
            NotificationService notificationService) {
        this.properties = properties;
        this.client = client;
        this.flowRepository = flowRepository;
        this.registrationRepository = registrationRepository;
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.authService = authService;
        this.reauthService = reauthService;
        this.mfaService = mfaService;
        this.termsService = termsService;
        this.profileValidator = profileValidator;
        this.rateLimitService = rateLimitService;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    // ---------------------------------------------------------------- start

    @Transactional
    public OauthStartResponse start(OauthStartRequest request, @Nullable AuthenticatedUser actor, String ip) {
        requireConfigured();
        rateLimitService.hit("oauth_start:ip", ip, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);

        OauthPurpose purpose = request.purpose() == null ? OauthPurpose.LOGIN : request.purpose();
        Long initiatingUserId = null;
        if (purpose != OauthPurpose.LOGIN) {
            if (actor == null) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_TOKEN_INVALID,
                        "인증이 필요합니다", "로그인한 뒤 다시 시도해 주세요.");
            }
            initiatingUserId = actor.id();
        }

        String state = TokenHasher.newToken();
        String nonce = TokenHasher.newToken();
        String codeVerifier = TokenHasher.newToken() + TokenHasher.newToken();
        Instant expiresAt = Instant.now().plus(FLOW_TTL);
        flowRepository.save(new OauthFlow(TokenHasher.sha256Hex(state), nonce, codeVerifier, purpose,
                initiatingUserId, internalPathOrNull(request.redirectTo()), expiresAt));

        // prompt=login only for REVERIFY. Forcing it on ordinary sign-in would
        // make people retype their Google password every time for no gain; not
        // forcing it on REVERIFY would let an existing Google session satisfy a
        // step-up that is supposed to prove presence.
        String url = client.authorizationUrl(state, nonce,
                pkceChallenge(codeVerifier), purpose == OauthPurpose.REVERIFY);
        return new OauthStartResponse(url, state, expiresAt);
    }

    // ------------------------------------------------------------- callback

    @Transactional
    public Object callback(OauthCallbackRequest request, String ip, String userAgent) {
        requireConfigured();
        rateLimitService.hit("oauth_callback:ip", ip, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);

        OauthFlow flow = flowRepository.findByStateHash(TokenHasher.sha256Hex(request.state()))
                .orElseThrow(GoogleOauthService::stateGone);
        // Conditional consume: a replayed state loses the UPDATE and gets 410.
        // A replayable state is a replayable login.
        if (flowRepository.consume(flow.getId(), Instant.now()) == 0) {
            throw stateGone();
        }

        GoogleOidcClient.GoogleIdentity identity =
                client.exchange(request.code(), flow.getCodeVerifier(), flow.getNonce());
        String email = identity.email().toLowerCase(Locale.ROOT);
        // Only now do we know the address, so this is where the per-account
        // counter can be applied at all.
        rateLimitService.hit("oauth_callback:acct", email, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);

        return switch (flow.getPurpose()) {
            case REVERIFY -> reverify(flow, identity, ip);
            case LINK -> link(flow, identity, ip);
            case LOGIN -> login(identity, email, ip, userAgent);
        };
    }

    private Object login(GoogleOidcClient.GoogleIdentity identity, String email, String ip,
            String userAgent) {
        // sub first, then address, then new. sub is the stable key: a Workspace
        // rename changes the address but not the subject, and matching on the
        // address first would strand the renamed account.
        Optional<UserIdentity> linked =
                identityRepository.findByProviderAndSubject(IdentityProvider.GOOGLE, identity.subject());
        if (linked.isPresent()) {
            UserIdentity link = linked.get();
            User user = userRepository.findById(link.getUserId()).orElseThrow(GoogleOauthService::stateGone);
            link.markLogin(Instant.now());
            if (!link.getEmailAtLink().equalsIgnoreCase(email)) {
                // The Workspace address moved. users.email is what invitations,
                // audit and notifications use to name a person, and it is unique,
                // so it is not rewritten from under them: record the drift and
                // leave the decision to an operator.
                auditService.record(user.getId(), user.getRole().name(),
                        AuditService.ACCOUNT_IDENTITY_LINKED, "user", user.getPublicId(),
                        Map.of("provider", "GOOGLE", "reason", "email_drift",
                                "identityEmail", email, "accountEmail", user.getEmail()), ip);
            }
            return sessionFor(user, email, ip, userAgent);
        }

        Optional<User> byEmail = userRepository.findByEmail(email);
        if (byEmail.isEmpty()) {
            return newRegistration(identity, email);
        }

        User user = byEmail.get();
        requireSignInAllowed(user);
        // Automatic link. For an ACTIVE account both credentials are anchored to
        // the same mailbox: the local account proved control of it by consuming
        // the signup token, and Google has just authenticated the holder of that
        // same address against the domain's own IdP.
        //
        // A PENDING_VERIFICATION account has proved no such thing, and that is
        // the pre-hijacking case: anyone can sign an address up with a password
        // of their choosing, and the account sits there holding it. Activating
        // it here without dropping that password hands the account over — the
        // attacker never needed the verification link they could not read, only
        // the password they set. See the PENDING branch below.
        identityRepository.save(new UserIdentity(user.getId(), IdentityProvider.GOOGLE,
                identity.subject(), email, identity.hostedDomain(), Instant.now()));
        boolean clearedPassword = false;
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            // Order and the explicit save both matter here.
            // invalidateOpenSignupVerifications runs a @Modifying query with
            // clearAutomatically = true, which detaches everything in the
            // persistence context — including this user. Activating first and
            // invalidating second silently threw the activation away (the row
            // stayed PENDING_VERIFICATION while the response said success).
            // Invalidate first, then activate, then merge the now-detached
            // instance back with save().
            authService.invalidateOpenSignupVerifications(user.getId());
            authService.activateAccount(user, Instant.now());
            // The password on a pending account came from whoever filled the
            // signup form, and that is not necessarily the person standing here
            // now — the mailbox was never proved. Activating without dropping it
            // is a complete account takeover: the attacker signs the victim's
            // address up with a password they know, waits for the victim's own
            // Google sign-in to activate it, and logs in.
            clearedPassword = user.hasPassword();
            user.clearPassword();
            user.bumpTokenVersion();
            user = userRepository.save(user);
        }
        auditService.record(user.getId(), user.getRole().name(), AuditService.ACCOUNT_IDENTITY_LINKED,
                "user", user.getPublicId(), Map.of("provider", "GOOGLE", "reason", "auto_link",
                        "passwordCleared", String.valueOf(clearedPassword)), ip);
        // The holder is told, because "an account was linked to yours" is
        // exactly the event someone needs to see if it was not them.
        notificationService.publish(user.getId(), NotificationEvent.ACCOUNT_IDENTITY_LINKED,
                Map.of("userId", user.getId(), "userEmail", user.getEmail(), "provider", "GOOGLE",
                        "passwordCleared", clearedPassword),
                "identity_linked:" + user.getId() + ":GOOGLE");
        return sessionFor(user, email, ip, userAgent);
    }

    private Object sessionFor(User user, String email, String ip, String userAgent) {
        requireSignInAllowed(user);
        // 2FA still applies. The platform's TOTP exists to survive a compromised
        // IdP or a stolen Google session, and an account with two entry paths of
        // different strength is only as strong as the weaker one.
        if (mfaService.isEnrolled(user.getId())) {
            return new AuthService.MfaChallenge(mfaService.issueLoginChallenge(user.getId()));
        }
        return authService.issueSession(user, ip, userAgent, Map.of("email", email, "method", "google"));
    }

    private OauthRegistrationResponse newRegistration(GoogleOidcClient.GoogleIdentity identity,
            String email) {
        String token = TokenHasher.newToken();
        Instant expiresAt = Instant.now().plus(REGISTRATION_TTL);
        registrationRepository.save(new OauthRegistration(TokenHasher.sha256Hex(token),
                IdentityProvider.GOOGLE, identity.subject(), email,
                // Truncated to the column: this value is only the form's prefill
                // and the form revalidates it at 50 anyway, so a long Google
                // display name should not turn the callback into a 500.
                truncate(identity.name(), 100),
                identity.hostedDomain(), expiresAt));
        return OauthRegistrationResponse.of(token, email,
                identity.name() == null ? "" : identity.name(), expiresAt);
    }

    private Object reverify(OauthFlow flow, GoogleOidcClient.GoogleIdentity identity, String ip) {
        User user = resolveInitiator(flow, identity);
        return reauthService.issueVerified(user, ip);
    }

    private Object link(OauthFlow flow, GoogleOidcClient.GoogleIdentity identity, String ip) {
        Long initiator = flow.getInitiatingUserId();
        if (initiator == null) {
            throw stateGone();
        }
        User user = userRepository.findById(initiator).orElseThrow(GoogleOauthService::stateGone);
        identityRepository.findByProviderAndSubject(IdentityProvider.GOOGLE, identity.subject())
                .ifPresent(existing -> {
                    throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.AUTH_OAUTH_ALREADY_LINKED,
                            "이미 연동된 구글 계정입니다",
                            "이 구글 계정은 다른 계정에 연동되어 있습니다.");
                });
        identityRepository.save(new UserIdentity(user.getId(), IdentityProvider.GOOGLE,
                identity.subject(), identity.email().toLowerCase(Locale.ROOT), identity.hostedDomain(),
                Instant.now()));
        auditService.record(user.getId(), user.getRole().name(), AuditService.ACCOUNT_IDENTITY_LINKED,
                "user", user.getPublicId(), Map.of("provider", "GOOGLE", "reason", "manual_link"), ip);
        return OauthLinkedResponse.of();
    }

    /**
     * For the purposes that act on a live session: the Google account that came
     * back must resolve to the very account that started the flow. Without this
     * a step-up could be satisfied by authenticating as somebody else entirely.
     */
    private User resolveInitiator(OauthFlow flow, GoogleOidcClient.GoogleIdentity identity) {
        Long initiator = flow.getInitiatingUserId();
        if (initiator == null) {
            throw stateGone();
        }
        Long linkedUserId = identityRepository
                .findByProviderAndSubject(IdentityProvider.GOOGLE, identity.subject())
                .map(UserIdentity::getUserId)
                .orElse(null);
        if (!initiator.equals(linkedUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.AUTH_OAUTH_DOMAIN_NOT_ALLOWED,
                    "다른 계정으로 인증했습니다", "현재 로그인한 계정의 구글 계정으로 다시 시도해 주세요.");
        }
        return userRepository.findById(initiator).orElseThrow(GoogleOauthService::stateGone);
    }

    // ------------------------------------------------------------- complete

    @Transactional
    public AuthService.AuthResult complete(OauthCompleteRequest request, String ip, String userAgent) {
        requireConfigured();
        rateLimitService.hit("oauth_complete:ip", ip, RateLimitService.DEFAULT_LIMIT_PER_MINUTE);

        OauthRegistration registration = registrationRepository
                .findByTokenHash(TokenHasher.sha256Hex(request.registrationToken()))
                .orElseThrow(GoogleOauthService::registrationGone);
        if (registrationRepository.consume(registration.getId(), Instant.now()) == 0) {
            throw registrationGone();
        }
        rateLimitService.hit("oauth_complete:acct", registration.getEmail(),
                RateLimitService.DEFAULT_LIMIT_PER_MINUTE);

        profileValidator.validate(request.position(), request.studentNo(), request.departmentCode(),
                request.departmentOther());
        // Between issuing the token and redeeming it, the address may have been
        // taken (a parallel signup) — answer as the link path would rather than
        // colliding on the unique index.
        if (userRepository.existsByEmail(registration.getEmail())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.AUTH_OAUTH_ALREADY_LINKED,
                    "이미 가입된 주소입니다", "이 주소로 이미 계정이 있습니다. 다시 로그인해 주세요.");
        }

        Instant now = Instant.now();
        // No password at all, and no PENDING_VERIFICATION step: Google asserted
        // email_verified for an address in our own Workspace domain, which is the
        // same mailbox a verification mail would have gone to.
        User user = new User(registration.getEmail(), null, request.name().strip());
        user.setProfile(request.position(),
                ProfileValidator.normalizeStudentNo(request.position(), request.studentNo()),
                request.departmentCode(),
                ProfileValidator.normalizeDepartmentOther(request.departmentOther()));
        user = userRepository.save(user);
        // Same transaction as the user row, exactly as signup does it: an
        // incomplete consent set has to roll the account back, not leave one
        // behind that has agreed to nothing.
        termsService.recordSignupConsents(user.getId(), request.consents());
        identityRepository.save(new UserIdentity(user.getId(), IdentityProvider.GOOGLE,
                registration.getSubject(), registration.getEmail(), registration.getHostedDomain(), now));
        authService.activateAccount(user, now);

        auditService.record(user.getId(), user.getRole().name(), AuditService.AUTH_SIGNUP,
                "user", user.getPublicId(), Map.of("email", user.getEmail(), "method", "google"), ip);
        return authService.issueSession(user, ip, userAgent,
                Map.of("email", user.getEmail(), "method", "google"));
    }

    // --------------------------------------------------------------- shared

    private void requireConfigured() {
        if (!properties.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.AUTH_OAUTH_NOT_CONFIGURED,
                    "구글 로그인을 사용할 수 없습니다", "이 환경에는 구글 로그인이 설정되어 있지 않습니다.");
        }
    }

    /**
     * DISABLED and WITHDRAWN are named rather than hidden behind a uniform
     * answer. The caller has just proved control of the address, so this is
     * their own account's state; and a Google login that succeeds and then
     * silently fails is a dead end nobody can diagnose. Password login keeps its
     * uniform 401.
     */
    private void requireSignInAllowed(User user) {
        if (user.getStatus() == UserStatus.WITHDRAWN || user.getStatus() == UserStatus.DISABLED) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCOUNT_INVALID_STATE,
                    "로그인할 수 없는 계정입니다",
                    "이 계정은 현재 사용할 수 없습니다. 운영자에게 문의해 주세요.");
        }
    }

    /**
     * Only an internal path survives. The API issues no redirect, so this is not
     * an open-redirect guard for us — it is what stops the console being handed
     * an absolute URL to navigate to after login.
     */
    private static @Nullable String internalPathOrNull(@Nullable String redirectTo) {
        if (redirectTo == null || redirectTo.isBlank()) {
            return null;
        }
        String candidate = redirectTo.strip();
        boolean internal = candidate.startsWith("/") && !candidate.startsWith("//");
        return internal ? candidate : null;
    }

    private static @Nullable String truncate(@Nullable String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String pkceChallenge(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private static ApiException stateGone() {
        return new ApiException(HttpStatus.GONE, ErrorCodes.AUTH_OAUTH_STATE_INVALID,
                "로그인 요청이 만료되었습니다", "구글 로그인을 처음부터 다시 시도해 주세요.");
    }

    private static ApiException registrationGone() {
        return new ApiException(HttpStatus.GONE, ErrorCodes.AUTH_OAUTH_REGISTRATION_EXPIRED,
                "가입 절차가 만료되었습니다", "구글 로그인을 처음부터 다시 시도해 주세요.");
    }
}
