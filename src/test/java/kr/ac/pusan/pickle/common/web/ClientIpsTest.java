package kr.ac.pusan.pickle.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Single-trusted-proxy X-Forwarded-For resolution: only the rightmost entry
 * (appended by our nginx) is trusted; client-supplied left entries must not
 * influence the resolved IP (rate-limit bypass hardening).
 */
class ClientIpsTest {

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
