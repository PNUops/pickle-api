package kr.ac.pusan.pickle.networkpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.time.Instant;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryPolicyEvidenceTest {

    @TempDir Path temporary;

    @Test
    void matchesTheCompactProofToProtectedManifestAndSha() throws Exception {
        var fixture = RecoveryEvidenceFixtures.create(temporary);
        assertThat(fixture.evidence().operationId())
                .isEqualTo(UUID.fromString("10000000-0000-4000-8000-000000000001"));
        assertThat(fixture.evidence().target().providerDigest()).hasSize(40);
        assertThatThrownBy(() -> RecoveryPolicyEvidence.load(fixture.manifest(),
                "0".repeat(64), fixture.proof(), fixture.proofSha(),
                Files.getOwner(fixture.manifest()).getName()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecoveryPolicyEvidence.load(fixture.manifest(),
                fixture.manifestSha(), fixture.proof(), fixture.proofSha(),
                Files.getOwner(fixture.manifest()).getName(),
                Instant.now().plus(Duration.ofMinutes(11))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease");
    }

    @Test
    void rejectsSymlinkAndPermissiveProof() throws Exception {
        var fixture = RecoveryEvidenceFixtures.create(temporary);
        Path link = temporary.resolve("linked-proof.json");
        Files.createSymbolicLink(link, fixture.proof());
        String owner = Files.getOwner(fixture.manifest()).getName();
        assertThatThrownBy(() -> RecoveryPolicyEvidence.load(fixture.manifest(),
                fixture.manifestSha(), link, fixture.proofSha(), owner))
                .isInstanceOf(IllegalArgumentException.class);
        Files.setPosixFilePermissions(fixture.proof(), java.nio.file.attribute.PosixFilePermissions
                .fromString("rw-r--r--"));
        assertThatThrownBy(() -> RecoveryPolicyEvidence.load(fixture.manifest(),
                fixture.manifestSha(), fixture.proof(), fixture.proofSha(), owner))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
