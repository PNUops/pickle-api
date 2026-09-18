package kr.ac.pusan.pickle.networkpolicy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Persists canonical CIDR snapshots with optimistic revisions. */
@Repository
public class SourcePolicyStore {

    public record Stored(long revision, List<String> allowedCidrs, Instant updatedAt) {
        public Stored {
            allowedCidrs = List.copyOf(allowedCidrs);
        }
    }

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SourcePolicyStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<Stored> domain(long domainId) {
        return read("domain_source_policies", "domain_id", domainId);
    }

    public Optional<Stored> portMapping(long mappingId) {
        return read("port_mapping_source_policies", "port_mapping_id", mappingId);
    }

    public Stored replaceDomain(long domainId, long expectedRevision,
            List<String> cidrs, long actorId) {
        return replace("domain_source_policies", "domain_id", domainId,
                expectedRevision, cidrs, actorId);
    }

    public Stored replacePortMapping(long mappingId, long expectedRevision,
            List<String> cidrs, long actorId) {
        return replace("port_mapping_source_policies", "port_mapping_id", mappingId,
                expectedRevision, cidrs, actorId);
    }

    private Optional<Stored> read(String table, String idColumn, long id) {
        return jdbc.query("select revision, allowed_cidrs, updated_at from " + table
                        + " where " + idColumn + " = ?",
                rs -> rs.next() ? Optional.of(row(rs.getLong(1), rs.getString(2),
                        rs.getTimestamp(3))) : Optional.empty(), id);
    }

    private Stored replace(String table, String idColumn, long id, long expectedRevision,
            List<String> cidrs, long actorId) {
        String json = objectMapper.writeValueAsString(cidrs);
        List<Stored> changed;
        if (expectedRevision == 0) {
            changed = jdbc.query("insert into " + table + " (" + idColumn
                            + ", revision, allowed_cidrs, updated_by) "
                            + "values (?, 1, ?::jsonb, ?) on conflict do nothing "
                            + "returning revision, allowed_cidrs, updated_at",
                    (rs, rowNum) -> row(rs.getLong(1), rs.getString(2), rs.getTimestamp(3)),
                    id, json, actorId);
        } else {
            changed = jdbc.query("update " + table
                            + " set revision = revision + 1, allowed_cidrs = ?::jsonb, "
                            + "updated_by = ?, updated_at = now() where " + idColumn
                            + " = ? and revision = ? returning revision, allowed_cidrs, updated_at",
                    (rs, rowNum) -> row(rs.getLong(1), rs.getString(2), rs.getTimestamp(3)),
                    json, actorId, id, expectedRevision);
        }
        if (changed.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.SOURCE_POLICY_REVISION_CONFLICT,
                    "출발지 정책이 먼저 변경되었습니다",
                    "최신 정책을 다시 불러온 뒤 변경 내용을 확인해 주세요.");
        }
        return changed.getFirst();
    }

    private Stored row(long revision, String json, Timestamp updatedAt) {
        return new Stored(revision, objectMapper.readValue(json, STRING_LIST), updatedAt.toInstant());
    }
}
