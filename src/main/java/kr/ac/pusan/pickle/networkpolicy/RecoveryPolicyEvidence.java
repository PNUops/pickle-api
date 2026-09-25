package kr.ac.pusan.pickle.networkpolicy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The small, non-secret recovery proof, bound to a separately protected manifest. */
public record RecoveryPolicyEvidence(UUID operationId, long fencingToken,
        String manifestSha256, String restoreReceiptSha256, String databaseName,
        String systemIdentifier, UUID vmPublicId, Instant vmUpdatedAt, String vmStatus,
        Place source, Place target, Identity identity, Policy policy, Instant observedAt,
        Instant ownerExpiresAt) {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> ROOT_KEYS = Set.of("schema", "operation_id", "fencing_token",
            "manifest_sha256", "restore_receipt_sha256", "database", "vm", "source",
            "target", "identity", "policy", "observed_at");

    public record Place(UUID nodePublicId, String name, int vmid,
            String configSha256, String providerDigest) {}

    public record Identity(String hostname, String ip, String mac, String bridge,
            String ipconfig0, String sshHostKeySha256) {}

    public record Policy(long revision, long generation, String desiredHash,
            Long appliedGeneration, String appliedHash, String state) {}

    public static RecoveryPolicyEvidence load(Path manifestPath, String expectedManifestSha,
            Path proofPath, String expectedProofSha) {
        return load(manifestPath, expectedManifestSha, proofPath, expectedProofSha, "root");
    }

    static RecoveryPolicyEvidence load(Path manifestPath, String expectedManifestSha,
            Path proofPath, String expectedProofSha, String expectedOwner) {
        return load(manifestPath, expectedManifestSha, proofPath, expectedProofSha,
                expectedOwner, Instant.now());
    }

    static RecoveryPolicyEvidence load(Path manifestPath, String expectedManifestSha,
            Path proofPath, String expectedProofSha, String expectedOwner, Instant now) {
        String manifestHash = sha(expectedManifestSha);
        JsonNode manifest = JSON.readTree(protectedBytes(manifestPath, 65536, manifestHash,
                expectedOwner));
        JsonNode proof = JSON.readTree(protectedBytes(proofPath, 16384,
                sha(expectedProofSha), expectedOwner));
        requireKeys(proof, ROOT_KEYS);
        if (number(proof, "schema") != 1 || number(manifest, "schema") != 1) {
            throw new IllegalArgumentException("Unsupported recovery evidence schema.");
        }
        JsonNode owner = object(manifest, "owner");
        Instant acquired = Instant.parse(string(owner, "acquired_at"));
        Instant expires = Instant.parse(string(owner, "expires_at"));
        RecoveryPolicyEvidence value = new RecoveryPolicyEvidence(
                UUID.fromString(string(proof, "operation_id")), number(proof, "fencing_token"),
                sha(string(proof, "manifest_sha256")),
                sha(string(proof, "restore_receipt_sha256")),
                string(object(proof, "database"), "name"),
                string(object(proof, "database"), "system_identifier"),
                UUID.fromString(string(object(proof, "vm"), "public_id")),
                Instant.parse(string(object(proof, "vm"), "updated_at")),
                string(object(proof, "vm"), "status"),
                place(object(proof, "source")), place(object(proof, "target")),
                identity(object(proof, "identity")), policy(object(proof, "policy")),
                Instant.parse(string(proof, "observed_at")), expires);
        if (!value.manifestSha256().equals(manifestHash) || value.fencingToken() <= 0
                || !"STOPPED".equals(value.vmStatus()) || !"PENDING".equals(value.policy().state())
                || value.source().vmid() == value.target().vmid()
                || value.source().vmid() <= 0 || value.target().vmid() <= 0
                || value.source().nodePublicId().equals(value.target().nodePublicId())
                || value.policy().generation() <= 0
                || value.policy().appliedGeneration() == null
                || value.policy().appliedGeneration() != value.policy().generation() - 1
                || !value.policy().desiredHash().equals(value.policy().appliedHash())) {
            throw new IllegalArgumentException("Recovery policy proof has inconsistent pins.");
        }
        if (!acquired.isBefore(expires) || value.observedAt().isBefore(acquired)
                || !value.observedAt().isBefore(expires)
                || value.observedAt().isAfter(now.plus(Duration.ofSeconds(30)))
                || !now.isBefore(expires)) {
            throw new IllegalArgumentException("Recovery owner lease or proof has expired.");
        }
        matchManifest(manifest, value);
        return value;
    }

    public void requireWriteWindow(Instant now) {
        if (!now.plus(Duration.ofSeconds(90)).isBefore(ownerExpiresAt)) {
            throw new IllegalStateException("Recovery owner lease has insufficient time for provider apply.");
        }
    }

    public void requireCommitWindow(Instant now) {
        if (!now.plus(Duration.ofSeconds(10)).isBefore(ownerExpiresAt)) {
            throw new IllegalStateException("Recovery owner lease expired before DB commit.");
        }
    }

    private static void matchManifest(JsonNode manifest, RecoveryPolicyEvidence proof) {
        if (!string(manifest, "operation_id").equals(proof.operationId().toString())
                || number(manifest, "fencing_token") != proof.fencingToken()
                || !string(object(manifest, "database"), "name").equals(proof.databaseName())
                || !string(object(manifest, "database"), "system_identifier")
                        .equals(proof.systemIdentifier())
                || !string(object(manifest, "vm"), "public_id")
                        .equals(proof.vmPublicId().toString())
                || !string(object(manifest, "vm"), "status").equals(proof.vmStatus())) {
            throw new IllegalArgumentException("Manifest and policy proof identities differ.");
        }
        for (String kind : Set.of("source", "target")) {
            Place place = "source".equals(kind) ? proof.source() : proof.target();
            JsonNode source = object(manifest, kind);
            if (!string(source, "node_public_id").equals(place.nodePublicId().toString())
                    || !string(source, "name").equals(place.name())
                    || number(source, "vmid") != place.vmid()
                    || !sha(string(source, "config_sha256")).equals(place.configSha256())) {
                throw new IllegalArgumentException("Manifest and policy proof locations differ.");
            }
        }
        JsonNode identity = object(manifest, "identity");
        if (!string(identity, "hostname").equals(proof.identity().hostname())
                || !string(identity, "ip").equals(proof.identity().ip())
                || !string(identity, "mac").equalsIgnoreCase(proof.identity().mac())
                || !string(identity, "guest_bridge").equals(proof.identity().bridge())
                || !sha(string(identity, "ssh_host_key_sha256"))
                        .equals(proof.identity().sshHostKeySha256())) {
            throw new IllegalArgumentException("Manifest and policy proof guest identities differ.");
        }
    }

    private static Place place(JsonNode node) {
        requireKeys(node, Set.of("node_public_id", "name", "vmid", "config_sha256",
                "provider_digest"));
        String digest = string(node, "provider_digest");
        if (!digest.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Provider digest is invalid.");
        }
        return new Place(UUID.fromString(string(node, "node_public_id")),
                string(node, "name"), Math.toIntExact(number(node, "vmid")),
                sha(string(node, "config_sha256")), digest);
    }

    private static Identity identity(JsonNode node) {
        requireKeys(node, Set.of("hostname", "ip", "mac", "guest_bridge", "ipconfig0",
                "ssh_host_key_sha256"));
        return new Identity(string(node, "hostname"), string(node, "ip"), string(node, "mac"),
                string(node, "guest_bridge"), string(node, "ipconfig0"),
                sha(string(node, "ssh_host_key_sha256")));
    }

    private static Policy policy(JsonNode node) {
        requireKeys(node, Set.of("revision", "desired_generation", "desired_hash",
                "applied_generation", "applied_hash", "apply_state"));
        JsonNode applied = node.get("applied_generation");
        return new Policy(number(node, "revision"), number(node, "desired_generation"),
                sha(string(node, "desired_hash")), applied == null || applied.isNull()
                        ? null : applied.longValue(), sha(string(node, "applied_hash")),
                string(node, "apply_state"));
    }

    private static byte[] protectedBytes(Path path, int maximum, String expectedSha,
            String expectedOwner) {
        try {
            PosixFileAttributes before = Files.readAttributes(path, PosixFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || Files.isSymbolicLink(path)
                    || !before.owner().getName().equals(expectedOwner)
                    || !before.permissions().equals(Set.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
                    || before.size() == 0 || before.size() > maximum) {
                throw new IllegalArgumentException("Recovery evidence file is not protected.");
            }
            ByteBuffer buffer = ByteBuffer.allocate(maximum + 1);
            try (SeekableByteChannel channel = Files.newByteChannel(path,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                    // Bounded read; a growing file is rejected below.
                }
            }
            if (buffer.position() == 0 || buffer.position() > maximum
                    || !before.fileKey().equals(Files.readAttributes(path,
                            PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())) {
                throw new IllegalArgumentException("Recovery evidence changed during read.");
            }
            byte[] raw = java.util.Arrays.copyOf(buffer.array(), buffer.position());
            if (!digest(raw).equals(expectedSha)) {
                throw new IllegalArgumentException("Recovery evidence SHA-256 differs.");
            }
            return raw;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read protected recovery evidence.", failure);
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static String sha(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected lowercase SHA-256 is required.");
        }
        return value;
    }

    private static JsonNode object(JsonNode parent, String key) {
        JsonNode node = parent.get(key);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Recovery evidence object is missing: " + key);
        }
        return node;
    }

    private static String string(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isString() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Recovery evidence text is missing: " + key);
        }
        return value.asText();
    }

    private static long number(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException("Recovery evidence integer is missing: " + key);
        }
        return value.longValue();
    }

    private static void requireKeys(JsonNode node, Set<String> expected) {
        if (!node.isObject() || !node.properties().stream()
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet()).equals(expected)) {
            throw new IllegalArgumentException("Recovery evidence keys differ from schema.");
        }
    }
}
