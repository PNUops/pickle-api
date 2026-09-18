package kr.ac.pusan.pickle.inventory;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

class CloneImagePinTest {

    @Test
    void retryRefusesRetargetedTemplateRatherThanCloningAnotherImage() {
        OsImage granted = image(1, 1, 1001, CatalogStatus.ACTIVE);
        OsImage original = image(2, 2, 1011, CatalogStatus.ACTIVE);
        CloneImagePin pin = CloneImagePin.from(original);
        assertThatCode(() -> pin.requireUnchanged(granted, original)).doesNotThrowAnyException();
        assertThatThrownBy(() -> pin.requireUnchanged(granted, image(2, 2, 1012, CatalogStatus.ACTIVE)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pin.requireUnchanged(granted, image(2, 3, 1011, CatalogStatus.ACTIVE)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pin.requireUnchanged(granted, image(3, 2, 1011, CatalogStatus.ACTIVE)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pin.requireUnchanged(granted, image(2, 2, 1011, CatalogStatus.DISABLED)))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @MethodSource("changedRevisionFields")
    void storedRevisionHashRejectsDriftEvenWhenBothCatalogRowsChangeTogether(String field, Object value) {
        OsImage granted = image(1, 1, 1001, CatalogStatus.ACTIVE);
        OsImage replica = image(2, 2, 1011, CatalogStatus.ACTIVE);
        CloneImagePin pin = CloneImagePin.from(replica);
        ReflectionTestUtils.setField(granted, field, value);
        ReflectionTestUtils.setField(replica, field, value);
        assertThatThrownBy(() -> pin.requireUnchanged(granted, replica))
                .isInstanceOf(IllegalStateException.class);
    }

    static Stream<Arguments> changedRevisionFields() {
        return Stream.of(Arguments.of("name", "another-os"), Arguments.of("version", 3),
                Arguments.of("osFamily", "debian"), Arguments.of("osVersion", "26.04"),
                Arguments.of("sshUsername", "another-user"), Arguments.of("minDiskGb", 20));
    }

    @Test
    void revisionHashIsSharedAcrossReplicasButIgnoresPresentationAndLocalStatus() {
        OsImage anchor = image(1, 1, 1001, CatalogStatus.DISABLED);
        OsImage replica = image(2, 2, 1011, CatalogStatus.ACTIVE);
        ReflectionTestUtils.setField(replica, "displayName", "Another display label");
        ReflectionTestUtils.setField(replica, "notes", "Another operator note");
        assertThat(CloneImagePin.revisionSha256(anchor)).isEqualTo(CloneImagePin.revisionSha256(replica));
        assertThatCode(() -> CloneImagePin.from(replica).requireUnchanged(anchor, replica))
                .doesNotThrowAnyException();
    }

    @Test
    void recoveredVmKeepsCreationProvenanceWithoutGainingRecloneAuthority() {
        CloneImagePin pin = CloneImagePin.from(image(2, 2, 1011, CatalogStatus.ACTIVE));
        assertThatCode(() -> pin.requireCurrentNode(2)).doesNotThrowAnyException();
        assertThatThrownBy(() -> pin.requireCurrentNode(3)).isInstanceOf(IllegalStateException.class);
        assertThat(pin.nodeId()).isEqualTo(2);
    }

    private OsImage image(long id, long node, int vmid, CatalogStatus status) {
        OsImage image = new OsImage("example-os", "Example OS", "ubuntu", "24.04", "ubuntu",
                vmid, node, 2, 10, status, null);
        ReflectionTestUtils.setField(image, "id", id);
        return image;
    }
}
