package kr.ac.pusan.pickle.relay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RelayMappingRetirementStoreTest {

    @Test
    void tupleHashMatchesTheConsumerWireFixture() {
        assertThat(RelayMappingRetirementStore.tupleHash(
                8, "udp", 10053, "192.0.2.9", 53, 22))
                .isEqualTo("7d909bea936f3ead089f7d75f333a3592a589822fbd6e4ef06cb43a3a8d3dbbf");
    }
}
