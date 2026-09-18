package kr.ac.pusan.pickle.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OsImageSelectionTest {

    @Test
    void replicaRegistrationAndRetirementNeverReplaceTheOriginalPublicIdentity() {
        OsImage original = image(1, 1, 1, CatalogStatus.ACTIVE);
        OsImage replica = image(2, 2, 1, CatalogStatus.ACTIVE);
        assertThat(OsImageSelection.selectable(List.of(original, replica), Set.of(1L, 2L)))
                .containsExactly(original);
        original.setStatus(CatalogStatus.DISABLED);
        assertThat(OsImageSelection.selectable(List.of(replica, original), Set.of(2L))).containsExactly(original);
        assertThat(OsImageSelection.isSelectable(original, List.of(original, replica), Set.of(2L))).isTrue();
        assertThat(original.getStatus()).isEqualTo(CatalogStatus.DISABLED);
        assertThat(OsImageSelection.canonicalOf(replica, List.of(replica, original)))
                .contains(original);
    }

    @Test
    void aNewerRevisionCannotMakeTheRequestedOldRevisionAvailable() {
        OsImage old = image(1, 1, 1, CatalogStatus.DISABLED);
        OsImage latest = image(2, 2, 2, CatalogStatus.ACTIVE);
        assertThat(OsImageSelection.selectable(List.of(latest, old), Set.of(2L))).containsExactly(latest);
        assertThat(OsImageSelection.isSelectable(old, List.of(old, latest), Set.of(2L))).isFalse();
    }

    @Test
    void inconsistentReplicaMetadataCannotCreateAnotherMeaningForOnePublicRevision() {
        OsImage original = image(1, 1, 1, CatalogStatus.DISABLED);
        OsImage replica = image(2, 2, 1, CatalogStatus.ACTIVE);
        ReflectionTestUtils.setField(replica, "sshUsername", "another-user");
        assertThat(OsImageSelection.selectable(List.of(original, replica), Set.of(2L))).isEmpty();
        assertThat(OsImageSelection.isSelectable(replica, List.of(original, replica), Set.of(2L))).isFalse();
    }

    @Test
    void aReplicaOnAnInactiveNodeIsNotASelectableCatalogOffering() {
        OsImage original = image(1, 1, 1, CatalogStatus.ACTIVE);
        assertThat(OsImageSelection.selectable(List.of(original), Set.of())).isEmpty();
        assertThat(OsImageSelection.isSelectable(original, List.of(original), Set.of())).isFalse();
    }

    @Test
    void deduplicationPreservesTheEstablishedCatalogDisplayOrder() {
        OsImage old = image(1, 1, 1, CatalogStatus.ACTIVE);
        OsImage latest = image(2, 1, 2, CatalogStatus.ACTIVE);
        OsImage replica = image(3, 2, 1, CatalogStatus.ACTIVE);
        assertThat(OsImageSelection.selectable(List.of(latest, old, replica), Set.of(1L, 2L)))
                .containsExactly(latest, old);
    }

    private OsImage image(long id, long node, int revision, CatalogStatus status) {
        OsImage image = new OsImage("example-os", "Example OS", "ubuntu", "26.04", "ubuntu",
                1000 + (int) id, node, revision, 10, status, null);
        ReflectionTestUtils.setField(image, "id", id);
        return image;
    }
}
