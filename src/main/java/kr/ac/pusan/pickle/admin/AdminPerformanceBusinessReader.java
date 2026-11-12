package kr.ac.pusan.pickle.admin;

import java.util.LinkedHashMap;
import java.util.Map;
import kr.ac.pusan.pickle.admin.dto.AdminPerformanceOverviewResponse.PerformanceOverviewBusiness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** One coherent business snapshot, independent of optional telemetry reads. */
@Service
public class AdminPerformanceBusinessReader {
    private final JdbcTemplate jdbc;
    public AdminPerformanceBusinessReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PerformanceOverviewBusiness read() {
        Map<String, Long> workspaces = counts("select kind::text as kind, count(*) as total from workspaces where deleted_at is null group by kind order by kind");
        Map<String, Long> requests = counts("select resource_type::text as kind, count(*) as total from requests where status='APPROVED' group by resource_type order by resource_type");
        return jdbc.queryForObject("""
                select (select count(*) from users where withdrawn_at is null) as accounts,
                       (select count(*) from workspaces where deleted_at is null) as workspaces,
                       (select count(*) from requests where status='APPROVED') as requests,
                       (select count(*) from vms) as vms,
                       (select count(*) from domains where status='ACTIVE') as domains
                """, (rs, rowNum) -> new PerformanceOverviewBusiness(rs.getLong("accounts"),
                        rs.getLong("workspaces"), workspaces, rs.getLong("requests"), requests,
                        rs.getLong("vms"), rs.getLong("domains")));
    }
    private Map<String, Long> counts(String sql) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query(sql, rs -> { result.put(rs.getString("kind"), rs.getLong("total")); });
        return Map.copyOf(result);
    }
}
