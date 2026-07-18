package kr.ac.pusan.pickle.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Client IP resolution behind the two-hop proxy chain. {@code X-Real-IP} (set by
 * the app-LXC nginx from Cloudflare's CF-Connecting-IP) is the true client and
 * wins; without it we fall back to the rightmost {@code X-Forwarded-For} entry,
 * then {@code getRemoteAddr()}. Client-supplied left entries must never influence
 * the resolved IP (rate-limit bypass hardening).
 */
class ClientIpsTest {

    @Test
    void prefersRealIpOverForwardedRightmostInTwoHopChain() {
        // Cloudflare → LXC100 (172.30.1.10) → app-LXC nginx → api: the XFF
        // rightmost is the LXC100 hop for ALL external traffic, so trusting it
        // would collapse every client into one bucket. X-Real-IP carries the
        // true client and must win.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.30.1.20");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 172.30.1.10");
        request.addHeader("X-Real-IP", "203.0.113.7");
        assertThat(ClientIps.clientIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void realIpWinsEvenWhenForwardedIsSpoofed() {
        // An attacker may forge X-Forwarded-For, but nginx overwrites X-Real-IP.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.30.1.20");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 7.7.7.7, 172.30.1.10");
        request.addHeader("X-Real-IP", "198.51.100.42");
        assertThat(ClientIps.clientIp(request)).isEqualTo("198.51.100.42");
    }

    @Test
    void usesRemoteAddrWithoutForwardedHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.30.1.20");
        assertThat(ClientIps.clientIp(request)).isEqualTo("172.30.1.20");
    }

    @Test
    void usesSingleForwardedEntryFromTrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        assertThat(ClientIps.clientIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void spoofedLeftEntriesDoNotChangeTheResolvedIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        // Attacker sends "X-Forwarded-For: 6.6.6.6"; the trusted proxy appends
        // the real peer, producing "6.6.6.6, 203.0.113.7".
        request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.7");
        assertThat(ClientIps.clientIp(request)).isEqualTo("203.0.113.7");

        MockHttpServletRequest rotating = new MockHttpServletRequest();
        rotating.setRemoteAddr("127.0.0.1");
        rotating.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2, 203.0.113.7");
        assertThat(ClientIps.clientIp(rotating)).isEqualTo("203.0.113.7");
    }

    @Test
    void blankOrMalformedHeaderFallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.30.1.20");
        request.addHeader("X-Forwarded-For", "  ");
        assertThat(ClientIps.clientIp(request)).isEqualTo("172.30.1.20");

        MockHttpServletRequest trailingComma = new MockHttpServletRequest();
        trailingComma.setRemoteAddr("172.30.1.20");
        trailingComma.addHeader("X-Forwarded-For", "6.6.6.6,");
        assertThat(ClientIps.clientIp(trailingComma)).isEqualTo("172.30.1.20");
    }
}
