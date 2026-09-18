package kr.ac.pusan.pickle.networkpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourcePolicyTest {

    @Test
    void anExplicitEmptyPolicyDeniesEverySource() {
        SourcePolicy policy = SourcePolicy.parse(List.of(), true);
        assertThat(policy.allows("192.0.2.1")).isFalse();
        assertThat(policy.allows("2001:db8::1")).isFalse();
    }

    @Test
    void selectedSourcesDoNotGrantOtherNetworks() {
        SourcePolicy policy = SourcePolicy.parse(List.of("192.0.2.0/24", "2001:db8::/32"), true);
        assertThat(policy.allows("192.0.2.1")).isTrue();
        assertThat(policy.allows("2001:db8:1::1")).isTrue();
        assertThat(policy.allows("198.51.100.1")).isFalse();
        assertThat(policy.allows("2001:db9::1")).isFalse();
    }

    @Test
    void ipv6IsRejectedWhenTheEnforcementPathCannotSupportIt() {
        assertThatThrownBy(() -> SourcePolicy.parse(List.of("192.0.2.0/24", "::/0"), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IPv6");
    }

    @Test
    void nullDoesNotMeanLegacyOrUnrestricted() {
        assertThatThrownBy(() -> SourcePolicy.parse(null, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourcePolicy.parse(Collections.singletonList(null), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicatesAndOversizedPoliciesAreRejected() {
        assertThatThrownBy(() -> SourcePolicy.parse(List.of("2001:DB8::/32", "2001:db8::/32"), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("중복");
        assertThatThrownBy(() -> SourcePolicy.parse(Collections.nCopies(SourcePolicy.MAX_CIDRS + 1,
                        "192.0.2.0/24"), true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void callersCannotMutateAnAlreadyValidatedPolicy() {
        List<CidrBlock> input = new ArrayList<>(List.of(CidrBlock.parse("192.0.2.0/24")));
        SourcePolicy policy = new SourcePolicy(input);
        input.add(CidrBlock.parse("0.0.0.0/0"));
        assertThat(policy.cidrValues()).containsExactly("192.0.2.0/24");
        assertThatThrownBy(() -> policy.allowedCidrs().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
