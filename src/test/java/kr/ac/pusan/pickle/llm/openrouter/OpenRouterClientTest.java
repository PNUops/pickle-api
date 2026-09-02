package kr.ac.pusan.pickle.llm.openrouter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.config.OpenRouterProperties;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The OpenRouter management client against a stub server: shapes, error
 * mapping, and the two hygiene rules — the management bearer is sent, and
 * neither it nor a created key's plaintext ever reaches an exception message.
 */
class OpenRouterClientTest {

    /** The account-scoped management credential every call here presents. */
    private static final String SECRET = "mgmt-test-secret";

    private static WireMockServer server;
    private OpenRouterClient client;

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        server.resetAll();
        client = new OpenRouterClient(new OpenRouterProperties(
                server.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(5)));
    }

    @Test
    void createKeyParsesThePlaintextAndHashAndSendsTheGrant() {
        server.stubFor(post(urlPathEqualTo("/keys"))
                .withHeader("Authorization", equalTo("Bearer mgmt-test-secret"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"key": "sk-or-v1-plain",
                                 "data": {"hash": "abc123", "name": "k-1"}}
                                """)));

        OpenRouterClient.CreatedKey created = client.createKey(SECRET, null, "k-1",
                new BigDecimal("5.00"), CreditLimitReset.MONTHLY,
                Instant.parse("2026-12-31T00:00:00Z"));

        assertThat(created.hash()).isEqualTo("abc123");
        assertThat(created.plaintext()).isEqualTo("sk-or-v1-plain");
        server.verify(postRequestedFor(urlPathEqualTo("/keys"))
                .withRequestBody(matchingJsonPath("$.name", equalTo("k-1")))
                .withRequestBody(matchingJsonPath("$[?(@.limit == 5.00)]"))
                .withRequestBody(matchingJsonPath("$.limit_reset", equalTo("monthly")))
                .withRequestBody(matchingJsonPath("$.include_byok_in_limit", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.expires_at",
                        equalTo("2026-12-31T00:00:00Z"))));
    }

    @Test
    void everyLimitWriteAssertsThatByokSpendCounts() {
        // The flag is the whole point of writing a limit: OpenRouter defaults
        // it to false, and a ceiling that excludes BYOK inference enforces
        // nothing while still reading as the granted amount on both sides.
        // It rides updates as well as creation because that is the only way a
        // key created before this rule, or edited in the OpenRouter console,
        // ever gets repaired.
        server.stubFor(patch(urlPathEqualTo("/keys/h9"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\": {\"hash\": \"h9\"}}")));

        client.updateLimit(SECRET, null, "h9", new BigDecimal("7.50"), CreditLimitReset.DAILY);

        server.verify(patchRequestedFor(urlPathEqualTo("/keys/h9"))
                .withRequestBody(matchingJsonPath("$[?(@.limit == 7.50)]"))
                .withRequestBody(matchingJsonPath("$.limit_reset", equalTo("daily")))
                .withRequestBody(matchingJsonPath("$.include_byok_in_limit", equalTo("true"))));
    }

    @Test
    void createWithoutAResetWindowOmitsTheField() {
        server.stubFor(post(urlPathEqualTo("/keys"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"key\": \"sk-or-x\", \"data\": {\"hash\": \"h\"}}")));

        client.createKey(SECRET, null, "k-2", BigDecimal.ONE, null, null);

        server.verify(postRequestedFor(urlPathEqualTo("/keys"))
                .withRequestBody(notMatching(".*limit_reset.*"))
                .withRequestBody(notMatching(".*expires_at.*")));
    }

    @Test
    void anErrorCarriesTheStatusAndMessageAndNeverTheCredential() {
        server.stubFor(post(urlPathEqualTo("/keys"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": {\"message\": \"invalid limit\"}}")));

        assertThatThrownBy(() -> client.createKey(SECRET, null, "k-3", BigDecimal.ONE, null, null))
                .isInstanceOfSatisfying(OpenRouterException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("HTTP 400");
                    assertThat(e.getMessage()).doesNotContain("invalid limit");
                    assertThat(e.getMessage()).doesNotContain("mgmt-test-secret");
                });
    }

    @Test
    void aCreateAnswerWithoutTheKeyIsAFailureNotANullCredential() {
        server.stubFor(post(urlPathEqualTo("/keys"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\": {\"hash\": \"h\"}}")));

        assertThatThrownBy(() -> client.createKey(SECRET, null, "k-4", BigDecimal.ONE, null, null))
                .isInstanceOf(OpenRouterException.class);
    }

    @Test
    void deleteTreatsA404AsAlreadyDone() {
        server.stubFor(delete(urlPathEqualTo("/keys/gone"))
                .willReturn(aResponse().withStatus(404)
                        .withBody("{\"error\": {\"message\": \"not found\"}}")));

        client.deleteKey(SECRET, null, "gone"); // no throw: the desired state holds
    }

    @Test
    void listKeysWalksThePaginationToTheEnd() {
        server.stubFor(get(urlEqualTo("/keys?include_disabled=true&offset=0"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data": [
                                  {"hash": "h1", "name": "a", "disabled": false, "limit": 5,
                                   "include_byok_in_limit": true, "usage": 1.25,
                                   "limit_remaining": 3.75},
                                  {"hash": "h2", "name": "b", "disabled": true}]}
                                """)));
        server.stubFor(get(urlEqualTo("/keys?include_disabled=true&offset=2"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\": []}")));

        List<OpenRouterClient.ManagedKey> keys = client.listKeys(SECRET, null);

        assertThat(keys).hasSize(2);
        assertThat(keys.get(0).hash()).isEqualTo("h1");
        assertThat(keys.get(0).limit()).isEqualByComparingTo("5");
        assertThat(keys.get(0).includeByokInLimit()).isTrue();
        // What the key has spent, as they count it — the money figure the
        // console shows so nobody has to open the OpenRouter console.
        assertThat(keys.get(0).usage()).isEqualByComparingTo("1.25");
        assertThat(keys.get(0).limitRemaining()).isEqualByComparingTo("3.75");
        assertThat(keys.get(1).disabled()).isTrue();
        // Absent reads as false, which the reconciler treats as divergence and
        // repairs. Reading absence as true would hide exactly the state this
        // flag exists to catch.
        assertThat(keys.get(1).includeByokInLimit()).isFalse();
        // A listing that reports no spend for a key leaves it unknown rather
        // than claiming zero.
        assertThat(keys.get(1).usage()).isNull();
    }

    /**
     * A scope whose credential came back empty must never reach the wire: an
     * empty bearer is a request that looks authenticated and is not.
     */
    @Test
    void aBlankManagementCredentialFailsClosedBeforeAnyRequest() {
        assertThatThrownBy(() -> client.deleteKey(" ", null, "h"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    void explicitWorkspaceIsScopedOnCreateAndListButNotSingleKeyPaths() {
        UUID workspaceId = UUID.fromString("20000000-0000-4000-8000-000000000001");
        server.stubFor(post(urlPathEqualTo("/keys"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"key\":\"runtime\",\"data\":{\"hash\":\"h1\","
                                + "\"workspace_id\":\"" + workspaceId + "\"}}")));
        server.stubFor(get(urlEqualTo("/keys?include_disabled=true&offset=0&workspace_id="
                        + workspaceId))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":[]}")));
        server.stubFor(get(urlEqualTo("/keys/h1"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":{\"hash\":\"h1\",\"workspace_id\":\""
                                + workspaceId + "\"}}")));
        server.stubFor(patch(urlEqualTo("/keys/h1"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        server.stubFor(delete(urlEqualTo("/keys/h1"))
                .willReturn(aResponse().withStatus(204)));

        OpenRouterClient.CreatedKey created = client.createKey("explicit-secret", workspaceId,
                "probe", BigDecimal.ZERO, null, null);
        client.listKeys("explicit-secret", workspaceId);
        client.getKey("explicit-secret", workspaceId, "h1");
        client.setDisabled("explicit-secret", workspaceId, "h1", true);
        client.deleteKey("explicit-secret", workspaceId, "h1");

        assertThat(created.workspaceId()).isEqualTo(workspaceId);
        server.verify(postRequestedFor(urlPathEqualTo("/keys"))
                .withRequestBody(matchingJsonPath("$.workspace_id", equalTo(workspaceId.toString())))
                .withRequestBody(notMatching(".*disabled.*")));
        assertThat(server.getAllServeEvents().stream()
                .filter(event -> event.getRequest().getUrl().startsWith("/keys/h1"))
                .allMatch(event -> !event.getRequest().getUrl().contains("workspace_id"))).isTrue();
    }
}
