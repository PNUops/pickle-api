package kr.ac.pusan.pickle.provisioning;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drift finding persistence. Writes follow the codebase CAS discipline:
 * observation is a native upsert keyed on the partial unique index
 * (kind, dedup_key) WHERE status='OPEN', and every OPEN→RESOLVED transition is
 * a guarded update whose 0-row result means "already resolved".
 */
public interface DriftFindingRepository
        extends JpaRepository<DriftFinding, Long>, JpaSpecificationExecutor<DriftFinding> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<DriftFinding> findByPublicId(UUID publicId);

    /**
     * Records one observation of a drift condition: inserts an OPEN finding or
     * bumps {@code last_seen_at}/{@code summary}/{@code detail} of the existing
     * OPEN row. PostgreSQL requires the conflict target to repeat the partial
     * index predicate ({@code WHERE status='OPEN'}) for it to match the V17
     * partial unique index.
     */
    @Transactional
    @Modifying
    @Query(value = """
            insert into drift_findings
                   (kind, vm_id, proxmox_vmid, node_name, summary, detail, dedup_key,
                    first_seen_at, last_seen_at)
            values (cast(:kind as drift_finding_kind), :vmId, :proxmoxVmid, :nodeName, :summary,
                    cast(:detail as jsonb), :dedupKey, :now, :now)
                on conflict (kind, dedup_key) where status = 'OPEN'
                do update set last_seen_at = excluded.last_seen_at,
                              summary      = excluded.summary,
                              detail       = excluded.detail,
                              node_name    = excluded.node_name
            """, nativeQuery = true)
    void observe(@Param("kind") String kind, @Param("vmId") Long vmId,
            @Param("proxmoxVmid") Integer proxmoxVmid, @Param("nodeName") String nodeName,
            @Param("summary") String summary, @Param("detail") String detail,
            @Param("dedupKey") String dedupKey, @Param("now") Instant now);

    default void observe(DriftFindingKind kind, Long vmId, Integer proxmoxVmid, String nodeName,
            String summary, String detail, String dedupKey, Instant now) {
        observe(kind.name(), vmId, proxmoxVmid, nodeName, summary, detail, dedupKey, now);
    }

    /**
     * Auto-resolve at end of a reconcile cycle: OPEN findings of {@code kind}
     * whose dedup key was NOT observed this cycle no longer hold — CAS them to
     * RESOLVED with {@code resolved_by} null (= resolved by the reconciler).
     * An empty seen-set resolves every OPEN finding of the kind.
     */
    @Transactional
    @Modifying
    @Query(value = """
            update drift_findings
               set status = 'RESOLVED', resolved_at = :now
             where kind = cast(:kind as drift_finding_kind)
               and status = 'OPEN'
               and dedup_key <> all(:seenKeys)
            """, nativeQuery = true)
    int autoResolveNotSeen(@Param("kind") String kind, @Param("seenKeys") String[] seenKeys,
            @Param("now") Instant now);

    default int autoResolveNotSeen(DriftFindingKind kind, Collection<String> seenKeys, Instant now) {
        return autoResolveNotSeen(kind.name(), seenKeys.toArray(String[]::new), now);
    }

    /** Resolves only one vendor-account namespace after that scope succeeds. */
    @Transactional
    @Modifying
    @Query(value = """
            update drift_findings
               set status = 'RESOLVED', resolved_at = :now
             where kind = cast(:kind as drift_finding_kind)
               and status = 'OPEN'
               and dedup_key like :prefix || '%'
               and dedup_key <> all(:seenKeys)
            """, nativeQuery = true)
    int autoResolveNotSeenInScope(@Param("kind") String kind,
            @Param("prefix") String prefix, @Param("seenKeys") String[] seenKeys,
            @Param("now") Instant now);

    default int autoResolveNotSeenInScope(DriftFindingKind kind, String prefix,
            Collection<String> seenKeys, Instant now) {
        return autoResolveNotSeenInScope(kind.name(), prefix,
                seenKeys.toArray(String[]::new), now);
    }

    /**
     * Manual resolve: CAS OPEN→RESOLVED recording the admin; 0 rows = already
     * resolved. Native SQL because HQL enum literals render a cast to a type
     * name derived from the Java class (same reason as the VmRepository
     * deletion CAS methods).
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(value = """
            update drift_findings
               set status = 'RESOLVED', resolved_at = :now, resolved_by = :resolvedBy,
                   resolution_note = :note
             where id = :id and status = 'OPEN'
            """, nativeQuery = true)
    int resolve(@Param("id") Long id, @Param("resolvedBy") Long resolvedBy,
            @Param("note") String note, @Param("now") Instant now);

    long countByStatus(DriftFindingStatus status);
}
