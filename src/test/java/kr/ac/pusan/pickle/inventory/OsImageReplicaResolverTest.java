package kr.ac.pusan.pickle.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class OsImageReplicaResolverTest {

    private final OsImageRepository images = mock(OsImageRepository.class);
    private final OsImageReplicaResolver resolver = new OsImageReplicaResolver(images);

    @Test
    void cloneUsesTheSelectedNodesTemplateWithoutRewritingTheGrantedImage() {
        OsImage granted = image(1L, 1001, 2, "ubuntu", 10);
        OsImage replica = image(2L, 1011, 2, "ubuntu", 10);
        when(images.findByNameAndVersionAndNodeIdAndStatus("example-os", 2, 2L, CatalogStatus.ACTIVE))
                .thenReturn(Optional.of(replica));

        OsImage resolved = resolver.resolve(granted, 2L);

        assertThat(resolved.getProxmoxVmid()).isEqualTo(1011);
        assertThat(granted.getNodeId()).isEqualTo(1L);
        assertThat(granted.getProxmoxVmid()).isEqualTo(1001);
        verify(images).findByNameAndVersionAndNodeIdAndStatus("example-os", 2, 2L, CatalogStatus.ACTIVE);
    }

    @Test
    void missingRevisionDoesNotFallBackToAnotherRevision() {
        assertThatThrownBy(() -> resolver.resolve(image(1L, 1001, 2, "ubuntu", 10), 2L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("버전 2");
    }

    @Test
    void identicalRevisionWithDifferentGuestSemanticsIsRejected() {
        OsImage granted = image(1L, 1001, 2, "ubuntu", 10);
        for (OsImage mismatch : new OsImage[] {
                image(2L, 1011, 3, "ubuntu", 10),
                image(2L, 1011, 2, "debian", 10),
                image(2L, 1011, 2, "ubuntu", 20)}) {
            when(images.findByNameAndVersionAndNodeIdAndStatus("example-os", 2, 2L, CatalogStatus.ACTIVE))
                    .thenReturn(Optional.of(mismatch));
            assertThatThrownBy(() -> resolver.resolve(granted, 2L)).isInstanceOf(IllegalStateException.class);
        }
    }

    private OsImage image(long node, int vmid, int version, String username, int minDisk) {
        return new OsImage("example-os", "Example OS", "ubuntu", "24.04", username,
                vmid, node, version, minDisk, CatalogStatus.ACTIVE, null);
    }
}
