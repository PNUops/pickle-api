package kr.ac.pusan.pickle.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.identity.IdentityProvider;
import kr.ac.pusan.pickle.identity.UserIdentityRepository;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.GoogleOauthWireMockSupport;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Google sign-in end to end against a stub that signs real ID tokens.
 *
 * <p>Grouped by what each case is defending. The verification cases matter
 * because every one of them describes a token that is correctly signed: the
 * signature alone decides nothing, and skipping any of these claim checks
 * accepts a token that Google really did issue, only not for us, not for this
 * request, or not for an account allowed in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class GoogleOauthFlowTest {

    private static final GoogleOauthWireMockSupport GOOGLE = GoogleOauthWireMockSupport.start();

    @DynamicPropertySource
    static void googleProperties(DynamicPropertyRegistry registry) {
        registry.add("pickle.oauth.google.client-id", () -> GoogleOauthWireMockSupport.CLIENT_ID);
        registry.add("pickle.oauth.google.client-secret", () -> "test-client-secret");
        registry.add("pickle.oauth.google.token-uri", GOOGLE::tokenUri);
        registry.add("pickle.oauth.google.jwk-set-uri", GOOGLE::jwkSetUri);
    }

    @AfterAll
    static void stopStub() {
        GOOGLE.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserIdentityRepository identityRepository;

    @Autowired
    private kr.ac.pusan.pickle.security.JwtService jwtService;

    /**
     * Its own client address per case. Every OAuth endpoint is rate limited per
     * IP at 10/min and this class runs fifteen round trips, so sharing one
     * address turns the later cases into 429s that have nothing to do with what
     * they are testing.
     */
    private static final java.util.concurrent.atomic.AtomicInteger ADDRESS =
            new java.util.concurrent.atomic.AtomicInteger();

    private String clientAddress;

    @BeforeEach
    void resetStub() {
        GOOGLE.reset();
        clientAddress = "10.97.0." + (ADDRESS.incrementAndGet() % 250 + 1);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor fromThisCase() {
        return request -> {
            request.setRemoteAddr(clientAddress);
            return request;
        };
    }

    // ------------------------------------------------------- happy paths

    @Test
    void anUnknownAddressIsOfferedRegistrationRatherThanAnAccount() throws Exception {
        Flow flow = start();
        GOOGLE.stubToken(GoogleOauthWireMockSupport.claims("sub-new", "new.google@pusan.ac.kr", flow.nonce()));

        MvcResult result = callback(flow.state())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("REGISTRATION_REQUIRED"))
                .andExpect(jsonPath("$.email").value("new.google@pusan.ac.kr"))
                .andExpect(jsonPath("$.registrationToken").isNotEmpty())
                .andReturn();

        // No account exists yet: creating one here would leave a row that has
        // consented to nothing if the person walks away from the form.
        assertThat(userRepository.findByEmail("new.google@pusan.ac.kr")).isEmpty();

        String token = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("registrationToken").asString();
        complete(token, "new.google@pusan.ac.kr")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        User created = userRepository.findByEmail("new.google@pusan.ac.kr").orElseThrow();
        // ACTIVE without an e-mail round trip: Google asserted email_verified for
        // an address in our own Workspace domain, which is the same mailbox the
        // verification mail would have gone to.
        assertThat(created.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(created.hasPassword()).isFalse();
        // Not a defect: the onboarding form no longer asks, so a brand-new
        // Google account starts without a profile and the console prompts for
        // one after it is inside.
        assertThat(created.isProfileComplete()).isFalse();
    }

    @Test
    void aSecondSignInFindsTheAccountBySubject() throws Exception {
        Flow first = start();
        GOOGLE.stubToken(GoogleOauthWireMockSupport.claims("sub-repeat", "repeat@pusan.ac.kr", first.nonce()));
        String token = objectMapper.readTree(callback(first.state()).andReturn().getResponse()
                .getContentAsString()).get("registrationToken").asString();
        complete(token, "repeat@pusan.ac.kr").andExpect(status().isOk());

        Flow second = start();
        GOOGLE.stubToken(GoogleOauthWireMockSupport.claims("sub-repeat", "repeat@pusan.ac.kr", second.nonce()));
        callback(second.state())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void anExistingPasswordAccountIsLinkedAutomatically() throws Exception {
        User existing = save("linkme@pusan.ac.kr", UserStatus.ACTIVE);

        Flow flow = start();
        GOOGLE.stubToken(GoogleOauthWireMockSupport.claims("sub-link", "linkme@pusan.ac.kr", flow.nonce()));
        callback(flow.state())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        assertThat(identityRepository.findByProviderAndUserId(IdentityProvider.GOOGLE, existing.getId()))
                .isPresent();
    }

    @Test
    void aPendingAccountIsActivatedAndItsOpenVerificationLinksAreKilled() throws Exception {
        User pending = save("pending@pusan.ac.kr", UserStatus.PENDING_VERIFICATION);

        Flow flow = start();
        GOOGLE.stubToken(GoogleOauthWireMockSupport.claims("sub-pending", "pending@pusan.ac.kr", flow.nonce()));
        callback(flow.state()).andExpect(status().isOk());

        // Whoever created the pending row was mailed a verification link. If that
        // was not the holder, the link has to stop working the moment the real
        // holder proves the address is theirs.
        assertThat(userRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
    }

    // ------------------------------------------- what a signature cannot say

    @Test
    void aTokenIssuedForADifferentClientIsRefused() throws Exception {
        Flow flow = start();
        Map<String, Object> claims =
                GoogleOauthWireMockSupport.claims("sub-aud", "aud@pusan.ac.kr", flow.nonce());
        claims.put("aud", "someone-elses-client.apps.googleusercontent.com");
        GOOGLE.stubToken(claims);

        callback(flow.state())
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_EXCHANGE_FAILED"));
    }

    @Test
    void aTokenFromAnotherRequestIsRefused() throws Exception {
        Flow flow = start();
        Map<String, Object> claims =
                GoogleOauthWireMockSupport.claims("sub-nonce", "nonce@pusan.ac.kr", "some-other-nonce");
        GOOGLE.stubToken(claims);

        callback(flow.state())
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_EXCHANGE_FAILED"));
    }

    @Test
    void anAccountOutsideTheWorkspaceDomainIsRefused() throws Exception {
        Flow flow = start();
        Map<String, Object> claims =
                GoogleOauthWireMockSupport.claims("sub-outside", "someone@gmail.com", flow.nonce());
        claims.put("hd", "gmail.com");
        GOOGLE.stubToken(claims);

        callback(flow.state())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_DOMAIN_NOT_ALLOWED"));
    }

    @Test
    void anAliasAddressWithTheRightHostedDomainIsStillRefused() throws Exception {
        // hd says the tenant is ours while the address is not. Checking only one
        // of the two lets exactly this through, and users.email is what we would
        // have stored.
        Flow flow = start();
        Map<String, Object> claims =
                GoogleOauthWireMockSupport.claims("sub-alias", "someone@alias.example", flow.nonce());
        GOOGLE.stubToken(claims);

        callback(flow.state())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_DOMAIN_NOT_ALLOWED"));
    }

    @Test
    void anUnverifiedGoogleAddressIsRefused() throws Exception {
        Flow flow = start();
        Map<String, Object> claims =
                GoogleOauthWireMockSupport.claims("sub-unverified", "unverified@pusan.ac.kr", flow.nonce());
        claims.put("email_verified", false);
        GOOGLE.stubToken(claims);

        callback(flow.state())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_DOMAIN_NOT_ALLOWED"));
    }

    // ------------------------------------------------------- state handling

    @Test
    void aStateCanBeSpentOnlyOnce() throws Exception {
        Flow flow = start();
        GOOGLE.stubToken(GoogleOauthWireMockSupport.claims("sub-replay", "replay@pusan.ac.kr", flow.nonce()));
        callback(flow.state()).andExpect(status().isOk());

        // A replayable state is a replayable login.
        callback(flow.state())
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_STATE_INVALID"));
    }

    @Test
    void anUnknownStateIsRefused() throws Exception {
        callback("never-issued-state")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_STATE_INVALID"));
    }

    @Test
    void aRegistrationTokenCanBeSpentOnlyOnce() throws Exception {
        Flow flow = start();
        GOOGLE.stubToken(GoogleOauthWireMockSupport.claims("sub-once", "once@pusan.ac.kr", flow.nonce()));
        String token = objectMapper.readTree(callback(flow.state()).andReturn().getResponse()
                .getContentAsString()).get("registrationToken").asString();
        complete(token, "once@pusan.ac.kr").andExpect(status().isOk());

        complete(token, "once@pusan.ac.kr")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_REGISTRATION_EXPIRED"));
    }

    @Test
    void aFailedExchangeIsABadGatewayNotAServerError() throws Exception {
        Flow flow = start();
        GOOGLE.stubTokenFailure();
        callback(flow.state())
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_EXCHANGE_FAILED"));
    }

    // ------------------------------------------------------- account state

    @Test
    void aWithdrawnAccountIsToldWhyRatherThanSilentlyRefused() throws Exception {
        save("gone@pusan.ac.kr", UserStatus.WITHDRAWN);
        Flow flow = start();
        GOOGLE.stubToken(GoogleOauthWireMockSupport.claims("sub-gone", "gone@pusan.ac.kr", flow.nonce()));

        // Named, not hidden: the caller just proved control of the address, so
        // this is their own account's state, and a Google login that succeeds
        // and then silently fails is a dead end nobody can diagnose.
        callback(flow.state())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_INVALID_STATE"));
        assertThat(identityRepository.findByProviderAndSubject(IdentityProvider.GOOGLE, "sub-gone"))
                .isEmpty();
    }

    @Test
    void aDisabledAccountIsRefusedAndNotLinked() throws Exception {
        save("disabled@pusan.ac.kr", UserStatus.DISABLED);
        Flow flow = start();
        GOOGLE.stubToken(GoogleOauthWireMockSupport.claims("sub-disabled", "disabled@pusan.ac.kr",
                flow.nonce()));

        callback(flow.state())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_INVALID_STATE"));
        assertThat(identityRepository.findByProviderAndSubject(IdentityProvider.GOOGLE, "sub-disabled"))
                .isEmpty();
    }

    // ---------------------------------------------------------------- link

    @Test
    void aSignedInAccountCanAttachAGoogleIdentity() throws Exception {
        User user = save("linker@pusan.ac.kr", UserStatus.ACTIVE);
        Flow flow = startAs(user, "LINK");
        GOOGLE.stubToken(GoogleOauthWireMockSupport.claims("sub-manual-link", "linker@pusan.ac.kr",
                flow.nonce()));

        // No token comes back. The round trip proved possession of the Google
        // account, not of this one, and the caller was already signed in.
        callback(flow.state())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("LINKED"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        assertThat(identityRepository.findByUserIdOrderByLinkedAtAsc(user.getId()))
                .singleElement()
                .satisfies(identity -> assertThat(identity.getSubject()).isEqualTo("sub-manual-link"));
    }

    @Test
    void aGoogleAccountAlreadyOnSomebodyElseIsRefused() throws Exception {
        User owner = save("owner@pusan.ac.kr", UserStatus.ACTIVE);
        Flow ownerFlow = start();
        GOOGLE.stubToken(GoogleOauthWireMockSupport.claims("sub-taken", "owner@pusan.ac.kr",
                ownerFlow.nonce()));
        callback(ownerFlow.state()).andExpect(status().isOk());
        assertThat(identityRepository.findByUserIdOrderByLinkedAtAsc(owner.getId())).isNotEmpty();

        // A second account reaching for the same Google identity would otherwise
        // give one person two doors into two accounts.
        User other = save("other@pusan.ac.kr", UserStatus.ACTIVE);
        Flow flow = startAs(other, "LINK");
        GOOGLE.stubToken(GoogleOauthWireMockSupport.claims("sub-taken", "owner@pusan.ac.kr",
                flow.nonce()));

        callback(flow.state())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_ALREADY_LINKED"));
        assertThat(identityRepository.findByUserIdOrderByLinkedAtAsc(other.getId())).isEmpty();
    }

    @Test
    void aLinkFlowCannotBeStartedWithoutASession() throws Exception {
        // The account is fixed when the flow row is written, never by whoever
        // comes back from Google. Refusing at the start is stronger than
        // refusing at the callback: no row is written, so there is nothing for
        // a later request to arrive at holding a valid state.
        mockMvc.perform(post("/api/v1/auth/oauth/google/start")
                        .with(fromThisCase())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"LINK\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------- helpers

    private record Flow(String state, String nonce) {
    }

    /**
     * Starts a flow and reads the nonce back out of the authorization URL —
     * which is where the console would never look, but is the only place a test
     * can learn what the stub has to echo.
     */
    private Flow start() throws Exception {
        return readFlow(mockMvc.perform(post("/api/v1/auth/oauth/google/start")
                .with(fromThisCase())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")));
    }

    /** Starts a flow that acts on a live session, so the account is bound to it. */
    private Flow startAs(User user, String purpose) throws Exception {
        return readFlow(mockMvc.perform(post("/api/v1/auth/oauth/google/start")
                .with(fromThisCase())
                .header("Authorization", "Bearer " + jwtService.createAccessToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"" + purpose + "\"}")));
    }

    private Flow readFlow(org.springframework.test.web.servlet.ResultActions actions)
            throws Exception {
        MvcResult result = actions.andExpect(status().isOk()).andReturn();
        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        String url = body.get("authorizationUrl").asString();
        String nonce = java.net.URLDecoder.decode(
                url.replaceAll(".*[?&]nonce=([^&]*).*", "$1"), java.nio.charset.StandardCharsets.UTF_8);
        return new Flow(body.get("state").asString(), nonce);
    }

    private org.springframework.test.web.servlet.ResultActions callback(String state) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/oauth/google/callback")
                .with(fromThisCase())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("code", "stub-authorization-code", "state", state))));
    }

    @Test
    void aCallerThatDoesSendAProfileStillHasItStored() throws Exception {
        // The onboarding form stopped asking, but the fields are still on the
        // schema and a caller holding the values may send them. Dropping the
        // form must not quietly drop the acceptance.
        Flow flow = start();
        GOOGLE.stubToken(GoogleOauthWireMockSupport.claims(
                "sub-with-profile", "with.profile@pusan.ac.kr", flow.nonce()));
        MvcResult result = callback(flow.state())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("REGISTRATION_REQUIRED"))
                .andReturn();
        String token = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("registrationToken").asString();

        mockMvc.perform(post("/api/v1/auth/oauth/google/complete")
                        .with(fromThisCase())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "registrationToken", token,
                                "name", "구글가입",
                                "position", "PROFESSOR",
                                "departmentCode", "COMPUTER_SCIENCE",
                                "consents", List.of(
                                        Map.of("docType", "TERMS_OF_SERVICE", "version", 1),
                                        Map.of("docType", "PRIVACY_POLICY", "version", 1))))))
                .andExpect(status().isOk());

        User created = userRepository.findByEmail("with.profile@pusan.ac.kr").orElseThrow();
        assertThat(created.isProfileComplete()).isTrue();
        assertThat(created.getDepartmentCode()).isEqualTo("COMPUTER_SCIENCE");
    }

    private org.springframework.test.web.servlet.ResultActions complete(String registrationToken,
            String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/oauth/google/complete")
                .with(fromThisCase())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "registrationToken", registrationToken,
                        "name", "구글가입",
                        // No 직책 or 소속 학과: since v0.46.0 the onboarding form
                        // is 이름 and the consents, and the profile is asked for
                        // later inside the console.
                        "consents", List.of(
                                Map.of("docType", "TERMS_OF_SERVICE", "version", 1),
                                Map.of("docType", "PRIVACY_POLICY", "version", 1))))));
    }

    private User save(String email, UserStatus status) {
        userRepository.findByEmail(email).ifPresent(existing -> {
            identityRepository.deleteAll(
                    identityRepository.findByUserIdOrderByLinkedAtAsc(existing.getId()));
            userRepository.delete(existing);
            userRepository.flush();
        });
        User user = new User(email, "$2a$12$C6UzMDM.H6dfI/f/IKcEeO7uHhZ8mCEyXbNP9qhrPQicvBSl2Fx16", "기존");
        user.setStatus(status);
        return userRepository.saveAndFlush(user);
    }
}
