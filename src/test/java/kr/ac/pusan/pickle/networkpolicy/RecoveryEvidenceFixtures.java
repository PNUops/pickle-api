package kr.ac.pusan.pickle.networkpolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.time.Instant;
import java.time.Duration;

/** Public example shape copied into this repo's test resources, with real local SHA pins. */
final class RecoveryEvidenceFixtures {

    record FilesAndProof(Path manifest, String manifestSha, Path proof, String proofSha,
            RecoveryPolicyEvidence evidence) {}

    private RecoveryEvidenceFixtures() {}

    static FilesAndProof create(Path folder) throws IOException {
        Instant now = Instant.now();
        String manifest = Files.readString(Path.of("src/test/resources/recovery-policy-manifest.json"))
                .replace("2026-09-23T11:59:00+00:00",
                        now.minus(Duration.ofMinutes(1)).toString())
                .replace("2026-09-23T12:05:00+00:00",
                        now.plus(Duration.ofMinutes(10)).toString());
        String manifestSha = hash(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String proof = Files.readString(Path.of("src/test/resources/recovery-policy-proof.json"))
                .replace("2026-09-23T12:00:00+00:00",
                        now.minus(Duration.ofSeconds(5)).toString())
                .replace("\"manifest_sha256\": \"" + "a".repeat(64) + "\"",
                        "\"manifest_sha256\": \"" + manifestSha + "\"");
        Path manifestPath = folder.resolve("manifest.json");
        Path proofPath = folder.resolve("proof.json");
        Files.writeString(manifestPath, manifest);
        Files.writeString(proofPath, proof);
        Set<PosixFilePermission> permissions = Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(manifestPath, permissions);
        Files.setPosixFilePermissions(proofPath, permissions);
        String proofSha = hash(proof.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String owner = Files.getOwner(manifestPath).getName();
        RecoveryPolicyEvidence evidence = RecoveryPolicyEvidence.load(manifestPath,
                manifestSha, proofPath, proofSha, owner);
        return new FilesAndProof(manifestPath, manifestSha, proofPath, proofSha, evidence);
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
