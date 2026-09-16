package kr.ac.pusan.pickle.inventory;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
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

    private OsImage image(long id, long node, int vmid, CatalogStatus status) {
        OsImage image = new OsImage("example-os", "Example OS", "ubuntu", "24.04", "ubuntu",
                vmid, node, 2, 10, status, null);
        ReflectionTestUtils.setField(image, "id", id);
        return image;
    }
}
