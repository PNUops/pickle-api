package kr.ac.pusan.pickle.ipam;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * IP allocation with duplicate-IP and quarantine guarantees (docs/plan/03).
 *
 * <p>Concurrency safety comes from the database, not application locks:
 * fresh addresses are claimed with {@code INSERT .. ON CONFLICT (ip) DO
 * NOTHING} against the global unique index, and quarantine-expired RELEASED
 * rows are reclaimed with a CAS UPDATE that re-checks status and quarantine
 * against the database clock. A losing racer simply moves to the next
 * candidate address.</p>
 */
@Service
public class IpamService {

    /** Fallback when the settings row is missing (docs/plan/03: default 1 day). */
    static final int DEFAULT_QUARANTINE_HOURS = 24;

    private final JdbcTemplate jdbcTemplate;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final IpPoolRepository poolRepository;
    private final IpAllocationRepository allocationRepository;

    public IpamService(JdbcTemplate jdbcTemplate, SettingsService settingsService,
            ObjectMapper objectMapper, IpPoolRepository poolRepository,
            IpAllocationRepository allocationRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.poolRepository = poolRepository;
        this.allocationRepository = allocationRepository;
    }

    /**
     * Allocates the lowest free address of the pool to the VM. Candidates are
     * enumerated in address order over the pool CIDR, skipping the network,
     * broadcast and gateway addresses, the pool's reserved ranges, ALLOCATED
     * rows, and RELEASED rows still inside the quarantine window
     * ({@code settings.ip_quarantine_hours}).
     *
     * @throws IpPoolExhaustedException when no candidate can be claimed
     */
    public IpAllocation allocate(long poolId, long vmId) {
        IpPool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new IllegalArgumentException("unknown ip pool: " + poolId));
        int quarantineHours = settingsService.integer(SettingsService.IP_QUARANTINE_HOURS,
                DEFAULT_QUARANTINE_HOURS);
        Ipv4.Cidr cidr = Ipv4.parseCidr(pool.getCidr());
        long gateway = Ipv4.toLong(pool.getGateway());
        List<ReservedRange> reserved = parseReservedRanges(pool.getReservedRanges());
        Map<Long, ExistingRow> existing = loadExisting(poolId);
        Instant quarantineCutoff = Instant.now().minus(Duration.ofHours(quarantineHours));

        for (long candidate = cidr.firstUsable(); candidate <= cidr.lastUsable(); candidate++) {
            if (candidate == gateway || isReserved(candidate, reserved)) {
                continue;
            }
            ExistingRow row = existing.get(candidate);
            Long claimedId;
            if (row == null) {
                claimedId = tryInsert(poolId, candidate, vmId);
            } else if (row.status() == AllocationStatus.RELEASED && row.releasedAt() != null
                    && !row.releasedAt().isAfter(quarantineCutoff)) {
                claimedId = tryReclaim(row.id(), vmId, quarantineHours);
            } else {
                // ALLOCATED, or RELEASED still in quarantine.
                continue;
            }
            if (claimedId != null) {
                return allocationRepository.findById(claimedId).orElseThrow();
            }
        }
        throw new IpPoolExhaustedException(poolId, pool.getName());
    }

    /**
     * Releases the allocation, starting the quarantine clock — but only while
     * it still belongs to the given VM. A stale caller (e.g. a delayed delete
     * retry) whose allocation was already released and reclaimed by another
     * VM is a no-op instead of yanking someone else's live address. Idempotent:
     * a repeat call does not reset {@code released_at}.
     *
     * @return true when this call actually released the allocation
     */
    public boolean release(long allocationId, long vmId) {
        return jdbcTemplate.update("""
                update ip_allocations
                   set status = 'RELEASED', released_at = now()
                 where id = ? and vm_id = ? and status = 'ALLOCATED'
                """, allocationId, vmId) == 1;
    }

    /**
     * Point-in-time occupancy of the pool for admin views ({@code GET
     * /admin/nodes}). {@code freeCount} is what {@link #allocate} could still
     * hand out right now: usable addresses (CIDR minus network/broadcast,
     * gateway and reserved ranges) minus ALLOCATED rows and RELEASED rows
     * still inside the quarantine window.
     */
    public PoolUsage poolUsage(long poolId) {
        IpPool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new IllegalArgumentException("unknown ip pool: " + poolId));
        Ipv4.Cidr cidr = Ipv4.parseCidr(pool.getCidr());
        long gateway = Ipv4.toLong(pool.getGateway());
        List<ReservedRange> reserved = parseReservedRanges(pool.getReservedRanges());
        long usable = cidr.lastUsable() - cidr.firstUsable() + 1;
        // Reserved ranges are operator-seeded and non-overlapping (docs/plan/03).
        for (ReservedRange range : reserved) {
            long from = Math.max(range.from(), cidr.firstUsable());
            long to = Math.min(range.to(), cidr.lastUsable());
            if (from <= to) {
                usable -= to - from + 1;
            }
        }
        if (gateway >= cidr.firstUsable() && gateway <= cidr.lastUsable()
                && !isReserved(gateway, reserved)) {
            usable--;
        }
        int quarantineHours = settingsService.integer(SettingsService.IP_QUARANTINE_HOURS,
                DEFAULT_QUARANTINE_HOURS);
        long allocated = jdbcTemplate.queryForObject(
                "select count(*) from ip_allocations where pool_id = ? and status = 'ALLOCATED'",
                Long.class, poolId);
        long quarantined = jdbcTemplate.queryForObject("""
                select count(*) from ip_allocations
                 where pool_id = ? and status = 'RELEASED'
                   and released_at > now() - make_interval(hours => ?)
                """, Long.class, poolId, quarantineHours);
        return new PoolUsage(pool, allocated, Math.max(0, usable - allocated - quarantined));
    }

    /** Occupancy snapshot for the contract {@code IpPoolSummary}. */
    public record PoolUsage(IpPool pool, long allocatedCount, long freeCount) {
    }

    /** Claims a never-allocated address; null when a concurrent insert won. */
    private Long tryInsert(long poolId, long candidate, long vmId) {
        return jdbcTemplate.query("""
                insert into ip_allocations (pool_id, ip, vm_id, status)
                values (?, ?::inet, ?, 'ALLOCATED')
                on conflict (ip) do nothing
                returning id
                """, rs -> rs.next() ? rs.getLong(1) : null, poolId, Ipv4.format(candidate), vmId);
    }

    /**
     * Reclaims a quarantine-expired RELEASED row; the WHERE clause re-checks
     * both conditions so a concurrent reclaim (or an application/database
     * clock gap) makes this a no-op returning null instead of a double grant.
     */
    private Long tryReclaim(long allocationId, long vmId, int quarantineHours) {
        return jdbcTemplate.query("""
                update ip_allocations
                   set status = 'ALLOCATED', vm_id = ?, allocated_at = now(), released_at = null
                 where id = ? and status = 'RELEASED'
                   and released_at <= now() - make_interval(hours => ?)
                returning id
                """, rs -> rs.next() ? rs.getLong(1) : null, vmId, allocationId, quarantineHours);
    }

    private Map<Long, ExistingRow> loadExisting(long poolId) {
        Map<Long, ExistingRow> rows = new HashMap<>();
        jdbcTemplate.query("""
                select id, ip, status, released_at from ip_allocations where pool_id = ?
                """, (RowCallbackHandler) rs -> {
            Instant releasedAt = rs.getTimestamp("released_at") == null
                    ? null : rs.getTimestamp("released_at").toInstant();
            rows.put(Ipv4.toLong(rs.getString("ip")), new ExistingRow(rs.getLong("id"),
                    AllocationStatus.valueOf(rs.getString("status")), releasedAt));
        }, poolId);
        return rows;
    }

    private List<ReservedRange> parseReservedRanges(String json) {
        JsonNode node = objectMapper.readTree(json);
        if (!node.isArray()) {
            return List.of();
        }
        List<ReservedRange> ranges = new ArrayList<>(node.size());
        node.forEach(range -> ranges.add(new ReservedRange(
                Ipv4.toLong(range.get("from").asString()), Ipv4.toLong(range.get("to").asString()))));
        return List.copyOf(ranges);
    }

    private boolean isReserved(long candidate, List<ReservedRange> reserved) {
        return reserved.stream().anyMatch(r -> r.from() <= candidate && candidate <= r.to());
    }

    /** Inclusive reserved range from the pool's {@code reserved_ranges} jsonb. */
    private record ReservedRange(long from, long to) {
    }

    private record ExistingRow(long id, AllocationStatus status, Instant releasedAt) {
    }
}
