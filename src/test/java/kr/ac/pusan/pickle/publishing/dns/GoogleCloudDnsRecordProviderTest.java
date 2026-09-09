package kr.ac.pusan.pickle.publishing.dns;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
import com.github.tomakehurst.wiremock.http.Fault;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import kr.ac.pusan.pickle.config.DnsProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

/**
 * The thin Cloud DNS client against a WireMock stand-in for both Google
 * endpoints: the service-account key file is parsed, the assertion is a
 * verifiable RS256 JWT with the right issuer, audience and scope, the token
 * is cached across calls, and each record operation maps to the expected
 * REST call with the idempotence the provider contract promises. No Spring
 * context.
 */
class GoogleCloudDnsRecordProviderTest {

    private static final String ZONE_PATH = "/dns/v1/projects/proj/managedZones/zone";
    private static final String RRSET = ZONE_PATH + "/rrsets/team-x.example.dev./A";
    private static final String CNAME_RRSET = ZONE_PATH + "/rrsets/team-x.example.dev./CNAME";
    private static final String TXT_RRSET = ZONE_PATH + "/rrsets/team-x.example.dev./TXT";

    private static WireMockServer google;
    private static KeyPair keyPair;

    @TempDir
    static Path keyDir;

    private GoogleCloudDnsRecordProvider provider;

    @BeforeAll
    static void start() throws Exception {
        google = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        google.start();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @AfterAll
    static void stop() {
        google.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        google.resetAll();
        google.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"tok-1\",\"expires_in\":3600,\"token_type\":\"Bearer\"}")));
        Path keyFile = keyDir.resolve("sa.json");
        Files.writeString(keyFile, keyJson());
        RestClient restClient = RestClient.builder().build();
        GoogleServiceAccountTokens tokens = GoogleServiceAccountTokens.load(keyFile, restClient);
        provider = new GoogleCloudDnsRecordProvider(restClient, tokens,
                google.baseUrl() + "/dns/v1", "proj", "zone");
    }

    @Test
    void ensureCreatesAMissingRecordWithASignedAssertionAndCachesTheToken() throws Exception {
        google.stubFor(get(urlPathEqualTo(RRSET)).willReturn(aResponse().withStatus(404)
                .withBody("{\"error\":{\"message\":\"not found\"}}")));
        google.stubFor(post(urlPathEqualTo(ZONE_PATH + "/rrsets")).willReturn(aResponse()
                .withStatus(200).withBody("{}")));

        provider.ensureA("team-x.example.dev", "203.0.113.10", 300);
        provider.removeA("team-x.example.dev");

        // One token exchange served both calls, and its assertion verifies
        // against the account's public key with the claims Google reads.
        google.verify(1, postRequestedFor(urlPathEqualTo("/token")));
        String form = google.findAll(postRequestedFor(urlPathEqualTo("/token"))).getFirst()
                .getBodyAsString();
        assertThat(form).startsWith("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer");
        String assertion = URLDecoder.decode(form.substring(form.indexOf("&assertion=") + 11),
                StandardCharsets.UTF_8);
        Claims claims = Jwts.parser().verifyWith(keyPair.getPublic()).build()
                .parseSignedClaims(assertion).getPayload();
        assertThat(claims.getIssuer()).isEqualTo("dns-writer@proj.iam.gserviceaccount.com");
        assertThat(claims.getAudience()).containsExactly(google.baseUrl() + "/token");
        assertThat(claims.get("scope", String.class)).isEqualTo(GoogleServiceAccountTokens.SCOPE);

        google.verify(postRequestedFor(urlPathEqualTo(ZONE_PATH + "/rrsets"))
                .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock
                        .equalTo("Bearer tok-1"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.equalToJson(
                        "{\"name\":\"team-x.example.dev.\",\"type\":\"A\",\"ttl\":300,"
                                + "\"rrdatas\":[\"203.0.113.10\"]}")));
    }

    @Test
    void ensureLeavesAnIdenticalRecordAloneAndPatchesADifferentOne() {
        google.stubFor(get(urlPathEqualTo(RRSET)).willReturn(aResponse().withStatus(200)
                .withBody("{\"name\":\"team-x.example.dev.\",\"type\":\"A\",\"ttl\":300,"
                        + "\"rrdatas\":[\"203.0.113.10\"]}")));
        provider.ensureA("team-x.example.dev", "203.0.113.10", 300);
        google.verify(0, postRequestedFor(urlPathEqualTo(ZONE_PATH + "/rrsets")));
        google.verify(0, patchRequestedFor(urlPathEqualTo(RRSET)));

        google.stubFor(patch(urlPathEqualTo(RRSET)).willReturn(aResponse().withStatus(200)
                .withBody("{}")));
        provider.ensureA("team-x.example.dev", "203.0.113.99", 300);
        google.verify(1, patchRequestedFor(urlPathEqualTo(RRSET))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing(
                        "203.0.113.99")));
    }

    @Test
    void eachTypeAddressesItsOwnRecordSet() {
        // The rrset endpoint is keyed by name AND type. A hardcoded /A here
        // would send a CNAME body to the A set, which the API accepts as a
        // type mismatch error rather than doing what was asked.
        google.stubFor(get(urlPathEqualTo(CNAME_RRSET)).willReturn(aResponse().withStatus(404)));
        google.stubFor(post(urlPathEqualTo(ZONE_PATH + "/rrsets")).willReturn(aResponse()
                .withStatus(200).withBody("{}")));

        provider.ensure("team-x.example.dev", DnsRecordType.CNAME,
                List.of("pages.example.com."), 300);

        google.verify(1, getRequestedFor(urlPathEqualTo(CNAME_RRSET)));
        google.verify(0, getRequestedFor(urlPathEqualTo(RRSET)));
        google.verify(postRequestedFor(urlPathEqualTo(ZONE_PATH + "/rrsets"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.equalToJson(
                        "{\"name\":\"team-x.example.dev.\",\"type\":\"CNAME\",\"ttl\":300,"
                                + "\"rrdatas\":[\"pages.example.com.\"]}")));

        google.stubFor(delete(urlPathEqualTo(CNAME_RRSET)).willReturn(aResponse().withStatus(200)
                .withBody("{}")));
        provider.remove("team-x.example.dev", DnsRecordType.CNAME);
        google.verify(1, com.github.tomakehurst.wiremock.client.WireMock
                .deleteRequestedFor(urlPathEqualTo(CNAME_RRSET)));
    }

    @Test
    void txtIsWrittenQuotedAndAnEquivalentSetIsLeftAlone() {
        google.stubFor(get(urlPathEqualTo(TXT_RRSET)).willReturn(aResponse().withStatus(404)));
        google.stubFor(post(urlPathEqualTo(ZONE_PATH + "/rrsets")).willReturn(aResponse()
                .withStatus(200).withBody("{}")));

        provider.ensure("team-x.example.dev", DnsRecordType.TXT, List.of("pv-abc123"), 300);

        google.verify(postRequestedFor(urlPathEqualTo(ZONE_PATH + "/rrsets"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.equalToJson(
                        "{\"name\":\"team-x.example.dev.\",\"type\":\"TXT\",\"ttl\":300,"
                                + "\"rrdatas\":[\"\\\"pv-abc123\\\"\"]}")));

        // The zone holds the same value split into two character-strings, which
        // is what a long value looks like and what another tool may have
        // written. Compared as raw strings that differs from what this client
        // would send and the record is rewritten on every reconcile, so the
        // comparison decodes both sides first. Written this way on purpose:
        // a stub echoing the exact presentation form this client produces
        // passes with or without the decoding and proves nothing.
        google.stubFor(get(urlPathEqualTo(TXT_RRSET)).willReturn(aResponse().withStatus(200)
                .withBody("{\"name\":\"team-x.example.dev.\",\"type\":\"TXT\",\"ttl\":300,"
                        + "\"rrdatas\":[\"\\\"pv-\\\" \\\"abc123\\\"\"]}")));
        // Stubbed before the equivalence check so that a client which rewrites
        // anyway fails on the assertion below rather than on an unstubbed call:
        // the message should name the invariant, not the missing stub.
        google.stubFor(patch(urlPathEqualTo(TXT_RRSET)).willReturn(aResponse().withStatus(200)
                .withBody("{}")));
        provider.ensure("team-x.example.dev", DnsRecordType.TXT, List.of("pv-abc123"), 300);
        google.verify(0, patchRequestedFor(urlPathEqualTo(TXT_RRSET)));

        // A different value still gets written.
        provider.ensure("team-x.example.dev", DnsRecordType.TXT, List.of("pv-zzz999"), 300);
        google.verify(1, patchRequestedFor(urlPathEqualTo(TXT_RRSET))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing(
                        "pv-zzz999")));
    }

    @Test
    void ensureFallsBackToPatchWhenTheCreateLosesARace() {
        google.stubFor(get(urlPathEqualTo(RRSET)).willReturn(aResponse().withStatus(404)));
        google.stubFor(post(urlPathEqualTo(ZONE_PATH + "/rrsets")).willReturn(aResponse()
                .withStatus(409).withBody("{\"error\":{\"message\":\"alreadyExists\"}}")));
        google.stubFor(patch(urlPathEqualTo(RRSET)).willReturn(aResponse().withStatus(200)
                .withBody("{}")));

        provider.ensureA("team-x.example.dev", "203.0.113.10", 300);

        google.verify(1, patchRequestedFor(urlPathEqualTo(RRSET)));
    }

    @Test
    void removeTreatsAnAbsentRecordAsDone() {
        google.stubFor(delete(urlPathEqualTo(RRSET)).willReturn(aResponse().withStatus(404)));
        provider.removeA("team-x.example.dev");

        google.stubFor(delete(urlPathEqualTo(RRSET)).willReturn(aResponse().withStatus(200)
                .withBody("{}")));
        provider.removeA("team-x.example.dev");
        google.verify(2, com.github.tomakehurst.wiremock.client.WireMock
                .deleteRequestedFor(urlPathEqualTo(RRSET)));
    }

    @Test
    void listFollowsPagesAndReportsEveryType() {
        google.stubFor(get(urlEqualTo(ZONE_PATH + "/rrsets?maxResults=500"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"rrsets":[
                          {"name":"example.dev.","type":"NS","ttl":21600,"rrdatas":["ns-1.example."]},
                          {"name":"a.example.dev.","type":"A","ttl":300,"rrdatas":["203.0.113.10"]}
                        ],"nextPageToken":"p2"}
                        """)));
        google.stubFor(get(urlEqualTo(ZONE_PATH + "/rrsets?maxResults=500&pageToken=p2"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"rrsets":[
                          {"name":"*.example.dev.","type":"A","ttl":300,"rrdatas":["203.0.113.10"]}
                        ]}
                        """)));

        List<DnsRecord> records = provider.listRecords("example.dev");

        assertThat(records).extracting(DnsRecord::name, DnsRecord::type)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("example.dev", "NS"),
                        org.assertj.core.groups.Tuple.tuple("a.example.dev", "A"),
                        org.assertj.core.groups.Tuple.tuple("*.example.dev", "A"));
        google.verify(2, getRequestedFor(urlPathEqualTo(ZONE_PATH + "/rrsets")));
    }

    @Test
    void aRefusalOrAnOutageSurfacesAsAProviderFailureWithTheReason() {
        google.stubFor(get(urlPathEqualTo(ZONE_PATH + "/rrsets")).willReturn(aResponse()
                .withStatus(403).withBody("{\"error\":{\"message\":\"Permission denied on zone\"}}")));
        assertThatThrownBy(() -> provider.listRecords("example.dev"))
                .isInstanceOf(DnsProviderException.class)
                .hasMessageContaining("HTTP 403")
                .hasMessageContaining("Permission denied on zone");

        google.stubFor(delete(urlPathEqualTo(RRSET)).willReturn(aResponse()
                .withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThatThrownBy(() -> provider.removeA("team-x.example.dev"))
                .isInstanceOf(DnsProviderException.class)
                .hasMessageContaining("연결 실패");
    }

    @Test
    void aRejectedTokenExchangeFailsTheOperationNotTheBoot() {
        google.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withStatus(401)
                .withBody("{\"error\":\"invalid_grant\"}")));
        assertThatThrownBy(() -> provider.removeA("team-x.example.dev"))
                .isInstanceOf(DnsProviderException.class)
                .hasMessageContaining("token endpoint HTTP 401");
    }

    @Test
    void theConfigFallsBackToUnconfiguredWhenTheKeyFileIsMissingOrTheSettingsAreBlank() {
        DnsProviderConfig config = new DnsProviderConfig();
        org.springframework.mock.env.MockEnvironment dev = new org.springframework.mock.env.MockEnvironment();
        dev.setActiveProfiles("dev");

        DnsRecordProvider missingFile = config.dnsRecordProvider(new DnsProperties(
                "google", new DnsProperties.Google("proj", "zone",
                        keyDir.resolve("absent.json").toString()), null, null, null, null), dev);
        assertThat(missingFile.configured()).isFalse();
        assertThatThrownBy(() -> missingFile.ensureA("x.example.dev", "203.0.113.10", 300))
                .isInstanceOf(DnsProviderException.class)
                .hasMessageContaining("서비스 계정 키");

        DnsRecordProvider blank = config.dnsRecordProvider(new DnsProperties(
                "google", new DnsProperties.Google("", "", null), null, null, null, null), dev);
        assertThat(blank.configured()).isFalse();

        DnsRecordProvider none = config.dnsRecordProvider(new DnsProperties(
                null, null, null, null, null, null), dev);
        assertThat(none.configured()).isFalse();

        DnsRecordProvider noop = config.dnsRecordProvider(new DnsProperties(
                "noop", null, null, null, null, null), dev);
        assertThat(noop.configured()).isTrue();

        DnsRecordProvider real = config.dnsRecordProvider(new DnsProperties(
                "google", new DnsProperties.Google("proj", "zone",
                        keyDir.resolve("sa.json").toString()), null, null, null, null), dev);
        assertThat(real).isInstanceOf(GoogleCloudDnsRecordProvider.class);
    }

    @Test
    void theNoopProviderIsRefusedOnProductionBecauseItWouldReportEveryNameApplied() {
        DnsProviderConfig config = new DnsProviderConfig();
        org.springframework.mock.env.MockEnvironment prod = new org.springframework.mock.env.MockEnvironment();
        prod.setActiveProfiles("prod");

        DnsRecordProvider provider = config.dnsRecordProvider(
                new DnsProperties("noop", null, null, null, null, null), prod);

        assertThat(provider.configured()).isFalse();
        assertThatThrownBy(() -> provider.ensureA("x.example.dev", "203.0.113.10", 300))
                .isInstanceOf(DnsProviderException.class)
                .hasMessageContaining("noop");
    }

    private static String keyJson() {
        // A key generated for this run, wrapped in PEM armor. # not-a-secret
        String pem = "-----BEGIN PRIVATE KEY-----\n" // # not-a-secret
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n"; // # not-a-secret
        return "{\"type\":\"service_account\",\"project_id\":\"proj\","
                + "\"client_email\":\"dns-writer@proj.iam.gserviceaccount.com\","
                + "\"private_key\":" + quote(pem) + ","
                + "\"token_uri\":\"" + google.baseUrl() + "/token\"}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\n", "\\n") + "\"";
    }
}
