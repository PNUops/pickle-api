package kr.ac.pusan.pickle.gpu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GpuDeviceStateTest {

    private static final Gpu GPU = new Gpu(1, UUID.fromString("9d798a36-a638-4481-9d08-9194b326bb63"),
            1, "gpu-example", "Example GPU", 32768, "hostpci0", GpuStatus.ACTIVE);

    @Test
    void aCurrentValueRowIsNotAnUnappliedChange() {
        GpuDeviceState state = state(Map.of("hostpci0", "mapping=gpu-example,rombar=0"),
                List.of(Map.of("key", "hostpci0", "value", "mapping=gpu-example,rombar=0")));

        assertThat(state.attached(GPU)).isTrue();
        assertThat(state.absent(GPU)).isFalse();
        assertThat(state.hasPendingDeviceChange()).isFalse();
        assertThatCode(() -> state.requireKnown(GPU)).doesNotThrowAnyException();
    }

    @Test
    void aPendingOnlyWriteCannotBeMistakenForAnAttachedGpu() {
        GpuDeviceState state = state(Map.of(),
                List.of(Map.of("key", "hostpci0", "pending", "mapping=gpu-example,rombar=0")));

        assertThat(state.absent(GPU)).isTrue();
        assertThat(state.attached(GPU)).isFalse();
        assertThat(state.hasPendingDeviceChange()).isTrue();
        assertThatThrownBy(() -> state.requireKnown(GPU)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aCurrentAttachmentWithPendingRemovalIsStillUnresolved() {
        GpuDeviceState state = state(Map.of("hostpci0", "mapping=gpu-example"),
                List.of(Map.of("key", "hostpci0", "value", "mapping=gpu-example", "delete", 1)));

        assertThat(state.attached(GPU)).isTrue();
        assertThat(state.hasPendingDeviceChange()).isTrue();
        assertThatThrownBy(() -> state.requireKnown(GPU)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aZeroDeleteFlagDoesNotTurnACurrentRowIntoAPendingRemoval() {
        GpuDeviceState state = state(Map.of("hostpci0", "mapping=gpu-example"),
                List.of(Map.of("key", "hostpci0", "value", "mapping=gpu-example", "delete", 0)));

        assertThat(state.hasPendingDeviceChange()).isFalse();
        assertThatCode(() -> state.requireKnown(GPU)).doesNotThrowAnyException();
    }

    @Test
    void anUnrelatedPendingSettingDoesNotInventADeviceChange() {
        GpuDeviceState state = state(Map.of("hostpci0", "mapping=gpu-example"),
                List.of(Map.of("key", "memory", "value", 8192, "pending", 16384)));

        assertThat(state.hasPendingDeviceChange()).isFalse();
        assertThatCode(() -> state.requireKnown(GPU)).doesNotThrowAnyException();
    }

    @Test
    void anotherPendingPciSlotStillPreventsSafeDeviceFinalization() {
        GpuDeviceState state = state(Map.of("hostpci0", "mapping=gpu-example"),
                List.of(Map.of("key", "hostpci1", "pending", "mapping=other-example")));

        assertThat(state.hasPendingDeviceChange()).isTrue();
        assertThatThrownBy(() -> state.requireKnown(GPU)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMappingMustBeAnExactPropertyValue() {
        for (String value : List.of("mapping=gpu-example-two", "mapping=other-gpu-example",
                "description=mapping=gpu-example", "mapping=GPU-EXAMPLE", "0000:01:00.0")) {
            GpuDeviceState state = state(Map.of("hostpci0", value), List.of());

            assertThat(state.attached(GPU)).as("config %s", value).isFalse();
            assertThat(state.absent(GPU)).isFalse();
            assertThatThrownBy(() -> state.requireKnown(GPU)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void mappingPropertyOrderDoesNotChangeTheDeviceIdentity() {
        for (String value : List.of("mapping=gpu-example", "mapping=gpu-example,rombar=0,pcie=1",
                "pcie=1,mapping=gpu-example,rombar=0")) {
            GpuDeviceState state = state(Map.of("hostpci0", value), List.of());

            assertThat(state.attached(GPU)).as("config %s", value).isTrue();
            assertThatCode(() -> state.requireKnown(GPU)).doesNotThrowAnyException();
        }
    }

    @Test
    void theMappingOnAnotherSlotMustNotBeFinalizedAsAnAbsentDevice() {
        GpuDeviceState state = state(Map.of("hostpci1", "mapping=gpu-example"), List.of());

        assertThat(state.attached(GPU)).isFalse();
        assertThatThrownBy(() -> state.requireKnown(GPU)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void verifiedAbsenceAndLivePowerAreIndependentFacts() {
        GpuDeviceState stopped = new GpuDeviceState("stopped", Map.of(), List.of());
        GpuDeviceState running = new GpuDeviceState("running", Map.of(), List.of());

        assertThat(stopped.stopped()).isTrue();
        assertThat(running.stopped()).isFalse();
        assertThat(stopped.absent(GPU)).isTrue();
        assertThat(running.absent(GPU)).isTrue();
        assertThatCode(() -> stopped.requireKnown(GPU)).doesNotThrowAnyException();
        assertThatCode(() -> running.requireKnown(GPU)).doesNotThrowAnyException();
    }

    @Test
    void unknownPowerOrMissingReadbackCannotConstructAKnownState() {
        for (String status : List.of("paused", "unknown", "")) {
            assertThatThrownBy(() -> new GpuDeviceState(status, Map.of(), List.of()))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> new GpuDeviceState("stopped", null, List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new GpuDeviceState("stopped", Map.of(), null))
                .isInstanceOf(IllegalStateException.class);
    }

    private static GpuDeviceState state(Map<String, Object> config, List<Map<String, Object>> pending) {
        return new GpuDeviceState("stopped", config, pending);
    }
}
