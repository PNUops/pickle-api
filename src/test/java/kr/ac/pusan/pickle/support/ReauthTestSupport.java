package kr.ac.pusan.pickle.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.security.JwtService;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Sudo-mode reauthentication for tests: {@code @RequireReauth} endpoints answer
 * 403 {@code REAUTH_REQUIRED} without a valid {@code X-Reauth-Token}, so every
 * suite that touches one needs a token for the CALLING user.
 *
 * <p>Two routes, both returning the raw token to send as the header:
 * {@link #reauthHeaderViaApi} exercises the real {@code POST /auth/reverify}
 * (use it where the issue flow itself is under test — it needs the account's
 * real password), while {@link #seededReauthHeader} writes the row directly.
 * Fixture-heavy suites mint identities with unusable password hashes and would
 * otherwise pay a bcrypt round per call, so the seeded variant is the default
 * there — it stores the same {@code sha256(raw)} + pinned {@code token_version}
 * the service does, so validation, expiry and invalidation behave identically.
 */
public final class ReauthTestSupport {

    /** Header the reauth gate reads (mirrors {@code ReauthInterceptor}). */
    public static final String HEADER = "X-Reauth-Token";

    private ReauthTestSupport() {
    }

    /** Issues a token through {@code POST /auth/reverify} (needs the real password). */
    public static String reauthHeaderViaApi(MockMvc mockMvc, ObjectMapper objectMapper,
            String accessToken, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/reverify")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("reauthToken").asString();
    }

    /** Seeds a live token for {@code userId} at the user's current token_version. */
    public static String seededReauthHeader(JdbcTemplate jdbcTemplate, long userId) {
        Integer tokenVersion = jdbcTemplate.queryForObject(
                "select token_version from users where id = ?", Integer.class, userId);
        return seededReauthHeader(jdbcTemplate, userId, tokenVersion == null ? 0 : tokenVersion);
    }

    /**
     * Seeds a live token for the owner of {@code accessToken} — the reauth gate
     * binds a token to its user, so helpers that take a bearer token as a
     * parameter can mint the matching header without threading user ids around.
     */
    public static String seededReauthFor(JdbcTemplate jdbcTemplate, JwtService jwtService,
            String accessToken) {
        // The token names its account by public id; the reverification row is
        // keyed by the internal one.
        return seededReauthHeader(jdbcTemplate, jdbcTemplate.queryForObject(
                "select id from users where public_id = ?::uuid", Long.class,
                jwtService.parse(accessToken).getSubject()));
    }

    /** Seeds a live token pinned to an explicit token_version. */
    public static String seededReauthHeader(JdbcTemplate jdbcTemplate, long userId,
            int tokenVersion) {
        String rawToken = "reauth-test-" + UUID.randomUUID();
        jdbcTemplate.update("""
                insert into auth_reverifications (user_id, token_hash, token_version,
                                                  expires_at, created_ip)
                values (?, ?, ?, now() + interval '10 minutes', '127.0.0.1')
                """, userId, sha256Hex(rawToken), tokenVersion);
        return rawToken;
    }

    /** Same hashing the service applies before storing/looking up a token. */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
