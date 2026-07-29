package kr.ac.pusan.pickle.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The startup drift detection between the relay rows the sync authentication
 * pins on and the configured list that confines those peers to the sync
 * surface: a relay address present only in the table would authenticate
 * normally while reaching the whole listener.
 */
class RelaySourceConsistencyCheckTest {

    @Test
    void aRelayAddressOutsideTheRestrictedListIsReported() {
        List<String> unconfined = RelaySourceConsistencyCheck.unconfined(
                List.of("10.100.100.1"), List.of("10.100.100.1", "10.100.100.2"));
        assertThat(unconfined).containsExactly("10.100.100.2");
    }

    @Test
    void aFullyCoveredRegistryReportsNothing() {
        assertThat(RelaySourceConsistencyCheck.unconfined(
                List.of("10.100.100.1", "10.100.100.2"),
                List.of("10.100.100.2", "10.100.100.1"))).isEmpty();
        assertThat(RelaySourceConsistencyCheck.unconfined(List.of("10.100.100.1"), List.of()))
                .isEmpty();
    }

    @Test
    void blankAddressesAndDuplicatesAreNotReportedTwice() {
        List<String> unconfined = RelaySourceConsistencyCheck.unconfined(List.of("10.100.100.1"),
                java.util.Arrays.asList("10.100.100.9", "10.100.100.9", "  ", null));
        assertThat(unconfined).containsExactly("10.100.100.9");
    }
}
