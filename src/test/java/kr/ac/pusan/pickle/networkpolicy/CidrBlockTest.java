package kr.ac.pusan.pickle.networkpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CidrBlockTest {

    @Test
    void ipv4MembershipIncludesBothBoundariesWithoutCrossingTheNetwork() {
        CidrBlock block = CidrBlock.parse("192.0.2.128/25");
        assertThat(block.contains("192.0.2.128")).isTrue();
        assertThat(block.contains("192.0.2.255")).isTrue();
        assertThat(block.contains("192.0.2.127")).isFalse();
        assertThat(block.contains("192.0.3.0")).isFalse();
        assertThat(block.contains("2001:db8::1")).isFalse();
    }

    @Test
    void ipv6CanonicalizationAndMembershipCoverPartialBytes() {
        CidrBlock block = CidrBlock.parse("2001:DB8:0:0:8000:0:0:0/65");
        assertThat(block.toString()).isEqualTo("2001:db8:0:0:8000::/65");
        assertThat(block.contains("2001:db8::ffff:ffff:ffff:ffff")).isTrue();
        assertThat(block.contains("2001:db8::7fff:ffff:ffff:ffff")).isFalse();
        assertThat(block.contains("192.0.2.1")).isFalse();
    }

    @Test
    void aHostLiteralBecomesOnlyThatHost() {
        assertThat(CidrBlock.host("192.0.2.7").toString()).isEqualTo("192.0.2.7/32");
        assertThat(CidrBlock.host("2001:db8::7").toString()).isEqualTo("2001:db8::7/128");
        assertThat(CidrBlock.host("2001:db8::7").contains("2001:db8::8")).isFalse();
    }

    @Test
    void unrestrictedPrefixesAreExplicitAndFamilySpecific() {
        assertThat(CidrBlock.parse("0.0.0.0/0").contains("255.255.255.255")).isTrue();
        assertThat(CidrBlock.parse("::/0").contains("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")).isTrue();
        assertThat(CidrBlock.parse("::/0").contains("127.0.0.1")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost/32", "example.com/32", "127.1/32", "2130706433/32",
            "192.000.2.1/32", "192.0.2.256/32", "192.0.2.0/-1", "192.0.2.0/33",
            "192.0.2.0/024", "192.0.2.1/24", "192.0.2.0/24 ", "192.0.2.0/24\ndeny all;",
            "2001:db8::1/64", "2001:db8::/129", "fe80::%eth0/64", "[::1]/128",
            "::ffff:192.0.2.1/128", "::ffff:c000:201/128", "::gggg/128", "::/0/0", ""})
    void unsafeOrAmbiguousInputIsRejected(String value) {
        assertThatThrownBy(() -> CidrBlock.parse(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalPrefixesHaveOneCanonicalIdentity() {
        assertThat(CidrBlock.parse("2001:DB8:0000:0000:0000:0000:0000:0000/32"))
                .isEqualTo(CidrBlock.parse("2001:db8::/32"));
        assertThat(CidrBlock.parse("::1/128").toString()).isEqualTo("::1/128");
    }
}
