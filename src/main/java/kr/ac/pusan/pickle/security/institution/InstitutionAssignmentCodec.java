package kr.ac.pusan.pickle.security.institution;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.user.UserRole;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A fixed storage format with no role conversion, implicit work, or unknown-field tolerance. */
public final class InstitutionAssignmentCodec {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public record Encoded(String mode, String payload) { }

    public Encoded encode(InstitutionAssignment assignment) {
        ObjectNode root = JSON.createObjectNode();
        var work = root.putArray("work");
        assignment.work().stream().map(InstitutionWork::id).sorted().forEach(work::add);
        ObjectNode scopes = root.putObject("scopes");
        assignment.scopes().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ObjectNode scope = scopes.putObject(entry.getKey().name());
            scope.put("mode", entry.getValue().mode().name());
            var ids = scope.putArray("workspaceIds");
            entry.getValue().workspaceIds().stream().sorted().map(UUID::toString).forEach(ids::add);
        });
        return new Encoded(assignment.mode().name(), JSON.writeValueAsString(root));
    }

    public InstitutionAssignment decode(UUID accountId, UUID institutionId, UserRole rowRole,
            @Nullable String mode, @Nullable String payload, @Nullable Long revision) {
        if (mode == null && payload == null && revision == null) {
            throw new InstitutionAssignmentStorageException(InstitutionAssignmentStorageException.Reason.UNCONVERTED,
                    "Institution assignment has not been explicitly converted");
        }
        if (mode == null || payload == null || revision == null || revision <= 0) {
            throw corrupt("Incomplete institution assignment storage");
        }
        InstitutionAssignment assignment;
        try {
            JsonNode root = JSON.readTree(payload);
            requireFields(root, Set.of("work", "scopes"));
            Set<InstitutionWork> work = readWork(root.get("work"));
            Map<InstitutionWorkGroup, InstitutionWorkspaceScope> scopes = readScopes(root.get("scopes"));
            assignment = new InstitutionAssignment(accountId, institutionId, InstitutionAssignment.Mode.valueOf(mode),
                    work, scopes, revision);
        } catch (InstitutionAssignmentStorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InstitutionAssignmentStorageException(InstitutionAssignmentStorageException.Reason.CORRUPT_PAYLOAD,
                    "Invalid institution assignment storage", exception);
        }
        requireRole(assignment, rowRole);
        return assignment;
    }

    static void requireRole(InstitutionAssignment assignment, UserRole rowRole) {
        if (assignment.displayRole() != rowRole) {
            throw new InstitutionAssignmentStorageException(InstitutionAssignmentStorageException.Reason.ROLE_MISMATCH,
                    "Stored work display role disagrees with the institution membership row");
        }
    }

    private static Set<InstitutionWork> readWork(JsonNode node) {
        if (node == null || !node.isArray()) { throw corrupt("Work must be an array"); }
        Set<InstitutionWork> work = new HashSet<>();
        for (JsonNode value : node) {
            if (!value.isString()) { throw corrupt("Work identifiers must be strings"); }
            InstitutionWork item = InstitutionWork.fromId(value.asString())
                    .orElseThrow(() -> corrupt("Unknown institution work identifier"));
            if (!work.add(item)) { throw corrupt("Duplicate institution work identifier"); }
        }
        return Set.copyOf(work);
    }

    private static Map<InstitutionWorkGroup, InstitutionWorkspaceScope> readScopes(JsonNode node) {
        if (node == null || !node.isObject()) { throw corrupt("Scopes must be an object"); }
        Map<InstitutionWorkGroup, InstitutionWorkspaceScope> scopes = new EnumMap<>(InstitutionWorkGroup.class);
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            InstitutionWorkGroup group = InstitutionWorkGroup.fromId(entry.getKey())
                    .orElseThrow(() -> corrupt("Unknown institution work group"));
            JsonNode value = entry.getValue();
            requireFields(value, Set.of("mode", "workspaceIds"));
            if (!value.get("mode").isString() || !value.get("workspaceIds").isArray()) {
                throw corrupt("Invalid workspace scope fields");
            }
            Set<UUID> ids = new HashSet<>();
            for (JsonNode id : value.get("workspaceIds")) {
                if (!id.isString()) { throw corrupt("Workspace identifiers must be strings"); }
                UUID workspaceId = UUID.fromString(id.asString());
                if (!workspaceId.toString().equals(id.asString()) || !ids.add(workspaceId)) {
                    throw corrupt("Workspace identifiers must be unique canonical UUIDs");
                }
            }
            scopes.put(group, new InstitutionWorkspaceScope(
                    InstitutionWorkspaceScope.Mode.valueOf(value.get("mode").asString()), ids));
        }
        return Map.copyOf(scopes);
    }

    private static void requireFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) { throw corrupt("Expected an assignment object"); }
        Set<String> actual = new HashSet<>();
        node.properties().forEach(entry -> actual.add(entry.getKey()));
        if (!actual.equals(expected)) { throw corrupt("Unexpected or missing assignment fields"); }
    }

    private static InstitutionAssignmentStorageException corrupt(String message) {
        return new InstitutionAssignmentStorageException(InstitutionAssignmentStorageException.Reason.CORRUPT_PAYLOAD, message);
    }
}
