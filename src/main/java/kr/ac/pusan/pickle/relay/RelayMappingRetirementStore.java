package kr.ac.pusan.pickle.relay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.config.RelayRetirementProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Consumer epoch allocation and exact resource-retirement receipts. */
@Repository
public class RelayMappingRetirementStore {

    public static final long UINT32_MAX = 4_294_967_295L;
    private final JdbcTemplate jdbc;
    private final RelayGenerations generations;
    private final RelayRetirementProperties properties;

    public RelayMappingRetirementStore(JdbcTemplate jdbc, RelayGenerations generations,
            RelayRetirementProperties properties) {
        this.jdbc = jdbc;
        this.generations = generations;
        this.properties = properties;
    }

    /** Requires a fresh capable handshake and advances both non-reusable identities atomically. */
    @Transactional
    public ConsumerEpoch allocateEpoch(long relayId) {
        if (!properties.enabled()) {
            throw unavailable("relay retirement producer가 비활성화되어 있습니다.");
        }
        ConsumerEpoch epoch = jdbc.query("""
                update relays
                   set mapping_id_high_water = greatest(mapping_id_high_water,
                           reported_mapping_id_high_water) + 1,
                       flow_mark_high_water = greatest(flow_mark_high_water,
                           reported_flow_mark_high_water) + 1,
                       updated_at = now()
                 where id = ? and retirement_armed and mark_namespace_ready
                   and retirement_ledger_id is not null
                   and retirement_observed_at > now() - interval '1 minute'
                   and greatest(mapping_id_high_water,
                                reported_mapping_id_high_water) < 9223372036854775807
                   and greatest(flow_mark_high_water, reported_flow_mark_high_water) < 4294967295
                returning mapping_id_high_water, flow_mark_high_water
                """, rs -> rs.next() ? new ConsumerEpoch(rs.getLong(1), rs.getLong(2)) : null,
                relayId);
        if (epoch == null) {
            throw unavailable("릴레이 retirement handshake가 신선하지 않거나 consumer ID/flow mark가 소진되었습니다.");
        }
        return epoch;
    }

    @Transactional
    public Retirement begin(long mappingId) {
        Long relayId = jdbc.query("select relay_id from port_mappings where id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, mappingId);
        if (relayId == null) {
            throw unavailable("퇴역할 mapping을 찾을 수 없습니다.");
        }
        // Relay first, mapping second: create/retire/sync share this lock order.
        jdbc.queryForObject("select mapping_generation from relays where id = ? for update",
                Long.class, relayId);
        Mapping tuple = jdbc.query("""
                select m.relay_id, m.vm_id, lower(m.proto), m.public_port,
                       host(a.ip), m.target_port, m.flow_mark, m.consumer_mapping_id
                  from port_mappings m
                  join vms v on v.id = m.vm_id
                  join ip_allocations a on a.id = v.ip_allocation_id
                 where m.id = ? and m.flow_mark is not null
                   and a.vm_id = v.id and a.status = 'ALLOCATED'
                 for update of m, v, a
                """, rs -> rs.next() ? new Mapping(rs.getLong(1), rs.getLong(2),
                        rs.getString(3), rs.getInt(4), rs.getString(5), rs.getInt(6),
                        rs.getLong(7), rs.getLong(8)) : null, mappingId);
        if (tuple == null) {
            throw unavailable("퇴역할 mapping의 exact tuple을 확인할 수 없습니다.");
        }
        Retirement existing = findByMappingEpoch(mappingId, tuple.consumerMappingId());
        if (existing != null) {
            return existing;
        }
        long generation = generations.bump(tuple.relayId());
        String hash = tupleHash(tuple.consumerMappingId(), tuple.proto(), tuple.publicPort(), tuple.targetAddr(),
                tuple.targetPort(), tuple.flowMark());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into relay_mapping_retirements
                    (id, relay_id, mapping_row_id, mapping_id, vm_id, retirement_sequence, generation,
                     protocol, public_port, target_addr, target_port, flow_mark, tuple_hash)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::inet, ?, ?, ?)
                """, id, tuple.relayId(), mappingId, tuple.consumerMappingId(), tuple.vmId(), generation, generation,
                tuple.proto().toUpperCase(), tuple.publicPort(), tuple.targetAddr(),
                tuple.targetPort(), tuple.flowMark(), hash);
        jdbc.update("""
                update port_mappings
                   set delivery_state = 'RETIRING', last_change_generation = ?, updated_at = now()
                 where id = ?
                """, generation, mappingId);
        return new Retirement(id, tuple.relayId(), mappingId, tuple.consumerMappingId(), tuple.vmId(), generation,
                tuple.flowMark(), hash, false);
    }

    public Retirement findByMapping(long mappingId) {
        return jdbc.query("""
                select id, relay_id, mapping_row_id, mapping_id, vm_id, generation, flow_mark, tuple_hash,
                       cleared_at is not null
                  from relay_mapping_retirements where mapping_row_id = ?
                 order by retirement_sequence desc limit 1
                """, rs -> rs.next() ? new Retirement(rs.getObject(1, UUID.class), rs.getLong(2),
                        rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6),
                        rs.getLong(7), rs.getString(8), rs.getBoolean(9)) : null, mappingId);
    }

    private Retirement findByMappingEpoch(long mappingRowId, long consumerMappingId) {
        return jdbc.query("""
                select id, relay_id, mapping_row_id, mapping_id, vm_id, generation, flow_mark,
                       tuple_hash, cleared_at is not null
                  from relay_mapping_retirements
                 where mapping_row_id = ? and mapping_id = ?
                """, rs -> rs.next() ? new Retirement(rs.getObject(1, UUID.class), rs.getLong(2),
                        rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6),
                        rs.getLong(7), rs.getString(8), rs.getBoolean(9)) : null,
                mappingRowId, consumerMappingId);
    }

    public boolean confirmed(Retirement retirement) {
        Boolean result = jdbc.queryForObject("""
                select cleared_at is not null
                       and receipt_generation = generation
                       and exists(select 1 from relays r where r.id = relay_id
                                  and r.applied_generation >= generation
                                  and r.acknowledged_retirement_high_water >= generation)
                  from relay_mapping_retirements where id = ? and mapping_row_id = ?
                    and generation = ? and flow_mark = ? and tuple_hash = ?
                """, Boolean.class, retirement.id(), retirement.mappingRowId(),
                retirement.generation(), retirement.flowMark(), retirement.tupleHash());
        return Boolean.TRUE.equals(result);
    }

    /** Drops the API tuple only after its durable ACK can be replayed to the consumer. */
    public boolean deleteAcknowledged(Retirement retirement) {
        return jdbc.update("""
                delete from relay_mapping_retirements rr
                 where rr.id = ? and rr.mapping_row_id = ? and rr.generation = ?
                   and rr.cleared_at is not null
                   and exists(select 1 from relays r where r.id = rr.relay_id
                              and r.acknowledged_retirement_high_water >= rr.generation)
                """, retirement.id(), retirement.mappingRowId(), retirement.generation()) == 1;
    }

    public static String tupleHash(long mappingId, String proto, int publicPort,
            String targetAddr, int targetPort, long flowMark) {
        String canonical = mappingId + "\n" + proto.toLowerCase() + "\n" + publicPort + "\n"
                + targetAddr + "\n" + targetPort + "\n" + flowMark + "\n";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ApiException unavailable(String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.SOURCE_POLICY_UNAVAILABLE,
                "릴레이 retirement을 적용할 수 없습니다", detail);
    }

    private record Mapping(long relayId, long vmId, String proto, int publicPort,
            String targetAddr, int targetPort, long flowMark, long consumerMappingId) {
    }

    public record ConsumerEpoch(long mappingId, long flowMark) {
    }

    public record Retirement(UUID id, long relayId, long mappingRowId, long mappingId, long vmId,
            long generation, long flowMark, String tupleHash, boolean cleared) {
    }
}
