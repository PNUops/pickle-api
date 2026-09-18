package kr.ac.pusan.pickle.networkpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.ac.pusan.pickle.config.NetworkPolicyProperties;
import org.junit.jupiter.api.Test;

class SourcePolicyProducerTest {

    @Test
    void featureOffOmitsOnlyUnarmedLegacyState() {
        SourcePolicyStore store = mock(SourcePolicyStore.class);
        when(store.domain(1)).thenReturn(Optional.empty());
        when(store.domain(2)).thenReturn(Optional.of(
                new SourcePolicyStore.Stored(1, List.of(), Instant.EPOCH)));
        SourcePolicyProducer producer = new SourcePolicyProducer(
                new NetworkPolicyProperties(false, List.of()), store);

        assertThat(producer.domain(1, false)).isEmpty();
        assertThatThrownBy(() -> producer.domain(1, true))
                .isInstanceOf(SourcePolicyUnavailableException.class);
        assertThatThrownBy(() -> producer.domain(2, false))
                .isInstanceOf(SourcePolicyUnavailableException.class);
    }

    @Test
    void featureOnDefaultsMissingPolicyToExplicitDeny() {
        SourcePolicyStore store = mock(SourcePolicyStore.class);
        when(store.domain(1)).thenReturn(Optional.empty());
        SourcePolicyProducer producer = new SourcePolicyProducer(
                new NetworkPolicyProperties(true, List.of()), store);

        assertThat(producer.domain(1, false)).hasValueSatisfying(
                policy -> assertThat(policy.allowedCidrs()).isEmpty());
    }

    @Test
    void portPresetReturnsTheConfiguredIpv4Subset() {
        NetworkPolicyProperties properties = new NetworkPolicyProperties(true,
                List.of("192.0.2.0/24", "2001:db8::/32"));

        assertThat(properties.campusPreset(false).cidrValues())
                .containsExactly("192.0.2.0/24");
        assertThat(properties.campusPreset(true).cidrValues())
                .containsExactly("192.0.2.0/24", "2001:db8::/32");
    }
}
