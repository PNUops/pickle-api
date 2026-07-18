package kr.ac.pusan.pickle.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** RFC 6238 conformance + Base32 round-trip for {@link TotpService}. */
class TotpServiceTest {

    private final TotpService totp = new TotpService();

    /** RFC 6238 Appendix B SHA1 seed "12345678901234567890" as a Base32 secret. */
    private String rfcSecret() {
        return TotpService.base32Encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void generatesRfc6238ReferenceCodes() {
        String secret = rfcSecret();
        // T=59s → step counter 1 → 8-digit 94287082 → 6-digit truncation 287082.
        assertThat(totp.generate(secret, 59L / 30)).isEqualTo("287082");
        // T=1111111109 → counter 37037036 → 8-digit 07081804 → 6-digit 081804.
        assertThat(totp.generate(secret, 1111111109L / 30)).isEqualTo("081804");
    }

    @Test
    void verifyAcceptsCurrentStepAndOneStepSkew() {
        String secret = rfcSecret();
        Instant at = Instant.ofEpochSecond(59);
        assertThat(totp.verify(secret, "287082", at)).isTrue();
        // one step earlier/later is still accepted (±30s skew window)
        assertThat(totp.verify(secret, totp.generate(secret, 59L / 30 - 1), at)).isTrue();
        assertThat(totp.verify(secret, totp.generate(secret, 59L / 30 + 1), at)).isTrue();
        // two steps away is rejected, as is a wrong/short code
        assertThat(totp.verify(secret, totp.generate(secret, 59L / 30 + 2), at)).isFalse();
        assertThat(totp.verify(secret, "000000", at)).isFalse();
        assertThat(totp.verify(secret, "12345", at)).isFalse();
    }

    @Test
    void base32RoundTrips() {
        byte[] data = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertThat(TotpService.base32Decode(TotpService.base32Encode(data))).isEqualTo(data);
    }

    @Test
    void generatedSecretIsUsableBase32() {
        String secret = totp.generateSecret();
        assertThat(secret).matches("[A-Z2-7]+");
        // a code generated for "now" verifies for "now"
        Instant now = Instant.now();
        String code = totp.generate(secret, now.getEpochSecond() / 30);
        assertThat(totp.verify(secret, code, now)).isTrue();
    }

    @Test
    void otpauthUriCarriesIssuerAndSecret() {
        String uri = totp.otpauthUri("gildong.hong@pusan.ac.kr", "JBSWY3DPEHPK3PXP");
        assertThat(uri).startsWith("otpauth://totp/Pickle:");
        assertThat(uri).contains("secret=JBSWY3DPEHPK3PXP").contains("issuer=Pickle");
    }
}
