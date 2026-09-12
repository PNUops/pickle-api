package kr.ac.pusan.pickle.gpu;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.gpu.dto.GpuRequestSpecResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL mutations are performed inside the owning service's transaction. */
@Repository
public class GpuStore {
    private final JdbcTemplate jdbc;

    public GpuStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() { return jdbc; }

    public List<Gpu> gpus() {
        return jdbc.query("select * from gpus order by model, id", GpuStore::gpu);
    }

    public Optional<Gpu> gpu(long id) {
        return jdbc.query("select * from gpus where id = ?", GpuStore::gpu, id).stream().findFirst();
    }

    public Optional<Gpu> gpu(UUID publicId) {
        return jdbc.query("select * from gpus where public_id = ?", GpuStore::gpu, publicId).stream().findFirst();
    }

    public Optional<GpuAllocation> allocation(long id) {
        return jdbc.query("select * from gpu_allocations where id = ?", GpuStore::allocation, id).stream().findFirst();
    }

    public Optional<GpuAllocation> allocation(UUID id) {
        return jdbc.query("select * from gpu_allocations where public_id = ?", GpuStore::allocation, id).stream().findFirst();
    }

    public GpuAllocation lock(long id) {
        return jdbc.queryForObject("select * from gpu_allocations where id = ? for update", GpuStore::allocation, id);
    }

    public List<GpuAllocation> all() {
        return jdbc.query("select * from gpu_allocations order by created_at desc, id desc", GpuStore::allocation);
    }

    public List<GpuAllocation> held() {
        return jdbc.query("select * from gpu_allocations where status in ('ALLOCATED','RELEASING') order by id", GpuStore::allocation);
    }

    public long queuePosition(GpuAllocation allocation) {
        return jdbc.queryForObject("""
                select count(*) from gpu_allocations where status = 'QUEUED'
                 and (priority > ? or (priority = ? and (queued_at, id) <= (?, ?)))
                """, Long.class, allocation.priority(), allocation.priority(), java.sql.Timestamp.from(allocation.queuedAt()), allocation.id());
    }

    public boolean available(long id) {
        return !Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from gpu_allocations where gpu_id = ?
                  and status in ('ALLOCATED','RELEASING'))
                """, Boolean.class, id));
    }

    public GpuRequestSpecResponse requestSpec(long requestId) {
        return jdbc.query("""
                select d.*, v.public_id as vm_public_id, v.name as vm_name, g.public_id as gpu_public_id
                  from gpu_request_details d left join vms v on v.id = d.vm_id
                  left join gpus g on g.id = d.granted_gpu_id where d.request_id = ?
                """, (rs, row) -> new GpuRequestSpecResponse(rs.getObject("vm_public_id", UUID.class),
                    rs.getString("vm_name"), rs.getInt("lease_hours"),
                    rs.getObject("granted_lease_hours", Integer.class), rs.getObject("gpu_public_id", UUID.class),
                    rs.getObject("granted_priority", Integer.class)), requestId).stream().findFirst().orElse(null);
    }

    private static Gpu gpu(ResultSet rs, int row) throws SQLException {
        return new Gpu(rs.getLong("id"), rs.getObject("public_id", UUID.class), rs.getLong("node_id"),
                rs.getString("mapping_name"), rs.getString("model"), rs.getInt("vram_mb"),
                rs.getString("hostpci_slot"), GpuStatus.valueOf(rs.getString("status")));
    }

    static GpuAllocation allocation(ResultSet rs, int row) throws SQLException {
        String reason = rs.getString("release_reason");
        return new GpuAllocation(rs.getLong("id"), rs.getObject("public_id", UUID.class),
                rs.getLong("request_id"), rs.getLong("workspace_id"), rs.getLong("org_id"), rs.getString("name"),
                rs.getObject("gpu_id", Long.class), rs.getObject("preferred_gpu_id", Long.class),
                rs.getObject("vm_id", Long.class), GpuAllocationStatus.valueOf(rs.getString("status")),
                GpuConnectionStatus.valueOf(rs.getString("connection_status")), rs.getInt("granted_lease_hours"),
                rs.getInt("priority"), instant(rs, "queued_at"), rs.getObject("granted_start_date", LocalDate.class),
                rs.getObject("granted_end_date", LocalDate.class), instant(rs, "allocated_at"),
                instant(rs, "lease_ends_at"), instant(rs, "unattached_since"), instant(rs, "attached_at"),
                reason == null ? null : GpuReleaseReason.valueOf(reason), rs.getObject("operation_id", UUID.class),
                rs.getString("error"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    static Instant instant(ResultSet rs, String name) throws SQLException {
        var timestamp = rs.getTimestamp(name);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
