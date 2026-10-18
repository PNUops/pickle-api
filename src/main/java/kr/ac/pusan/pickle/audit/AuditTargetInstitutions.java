package kr.ac.pusan.pickle.audit;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fixed typed targets with construction-time institution relations, never an actor's current membership. */
final class AuditTargetInstitutions {
    private static final List<String> PORT_VM_REFERENCE_ACTIONS = List.of(AuditService.PORT_MAPPING_SUSPEND,
            AuditService.PORT_MAPPING_UNSUSPEND, AuditService.PORT_MAPPING_DELETE, AuditService.PORT_MAPPING_GUARDS_UPDATE);
    private static final Map<String, Binding> BINDINGS = Map.ofEntries(
            Map.entry("org", new Binding("orgs t", "t.id", true)),
            Map.entry("request", new Binding("requests t", "t.org_id", true)),
            Map.entry("request_recipient", new Binding("request_recipients t join requests r on r.id=t.request_id", "r.org_id")),
            Map.entry("vm", new Binding("vms t", "t.org_id", true)),
            Map.entry("llm_key", new Binding("llm_api_keys t", "t.org_id")),
            Map.entry("llm_key_body", new Binding("llm_request_bodies t join llm_api_keys k on k.id=t.key_id", "k.org_id")),
            Map.entry("gpu_allocation", new Binding("gpu_allocations t", "t.org_id")),
            Map.entry("gpu_reclaim_review", new Binding("gpu_reclaim_reviews t join gpu_allocations g on g.id=t.allocation_id", "g.org_id")),
            Map.entry("domain", new Binding("domains t", "t.org_id", true)),
            Map.entry("dns_domain", new Binding("domains t", "t.org_id")),
            Map.entry("route", new Binding("routes t join domains d on d.id=t.domain_id", "d.org_id", true)),
            Map.entry("port_mapping", new Binding("port_mappings t join vms v on v.id=t.vm_id", "v.org_id", true)),
            Map.entry("campus_ip_request", new Binding("campus_ip_requests t join vms v on v.id=t.vm_id", "v.org_id", true)),
            Map.entry("provisioning_task", new Binding("provisioning_tasks t join vms v on v.id=t.vm_id", "v.org_id", true)),
            Map.entry("openrouter_account", new Binding("openrouter_accounts t", "t.org_id")),
            Map.entry("announcement", new Binding("(select id,public_id,org_id from announcements where scope='ORG') t", "t.org_id", true)));

    private final JdbcTemplate jdbc;

    AuditTargetInstitutions(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<Target> resolve(String action, String type, UUID id, Map<String, Object> detail) {
        if ("domain_root".equals(type) && AuditService.DOMAIN_ROOT_POLICY.equals(action)
                && detail != null && detail.get("rootDomain") instanceof String root) {
            return jdbc.query("select o.id,o.name from domain_roots t join orgs o on o.id=t.org_id where t.root_domain=?",
                    (rs, row) -> new Target(rs.getLong(1), rs.getString(2)), root).stream().findFirst();
        }
        if (type == null || id == null) { return Optional.empty(); }
        Binding binding = BINDINGS.get(type);
        if (binding == null) { return Optional.empty(); }
        Optional<Target> target = resolve(binding, id);
        if (target.isEmpty() && "port_mapping".equals(type) && PORT_VM_REFERENCE_ACTIONS.contains(action)
                && detail != null) {
            Object vmId = detail.get("vmId");
            if (vmId instanceof UUID uuid) { return resolve(BINDINGS.get("vm"), uuid); }
            if (vmId instanceof String value) {
                try { return resolve(BINDINGS.get("vm"), UUID.fromString(value)); }
                catch (IllegalArgumentException invalid) { return Optional.empty(); }
            }
        }
        if (target.isEmpty() && "llm_key_body".equals(type) && AuditService.LLM_KEY_BODY_READ.equals(action)
                && detail != null && detail.get("keyId") instanceof String keyId) {
            // This exact action saves the server-verified key identity before body retention can delete its row.
            try { return resolve(BINDINGS.get("llm_key"), UUID.fromString(keyId)); }
            catch (IllegalArgumentException invalid) { return Optional.empty(); }
        }
        return target;
    }

    private Optional<Target> resolve(Binding binding, UUID id) {
        return jdbc.query("select o.id,o.name from " + binding.from() + " join orgs o on o.id=" + binding.orgId()
                        + " where t.public_id=?", (rs, row) -> new Target(rs.getLong(1), rs.getString(2)), id)
                .stream().findFirst();
    }

    /** Only target types predating public IDs may resolve their documented, retained internal identifiers. */
    static String verifiedIdSql() {
        StringBuilder sql = new StringBuilder("coalesce(a.target_org_id,case a.target_type");
        BINDINGS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Binding binding = entry.getValue();
            sql.append(" when '").append(entry.getKey()).append("' then ");
            if ("llm_key_body".equals(entry.getKey()) || "port_mapping".equals(entry.getKey())) { sql.append("coalesce("); }
            sql.append("(select ").append(binding.orgId()).append(" from ").append(binding.from())
                    .append(" where (t.public_id=").append(uuidSql("a.target_id"));
            if (binding.legacyNumeric()) { sql.append(" or t.id=").append(legacyIdSql("a.target_id")); }
            sql.append("))");
            if ("llm_key_body".equals(entry.getKey())) {
                sql.append(",case when a.action='").append(AuditService.LLM_KEY_BODY_READ)
                        .append("' then (select k.org_id from llm_api_keys k where k.public_id=")
                        .append(uuidSql("(a.detail->>'keyId')")).append(") end)");
            }
            if ("port_mapping".equals(entry.getKey())) {
                sql.append(",case when a.action in ('").append(String.join("','", PORT_VM_REFERENCE_ACTIONS))
                        .append("') then (select v.org_id from vms v where v.public_id=")
                        .append(uuidSql("(a.detail->>'vmId')")).append(" or v.id=")
                        .append(legacyIdSql("(a.detail->>'vmId')")).append(") end)");
            }
        });
        sql.append(" when 'domain_root' then case when a.action='").append(AuditService.DOMAIN_ROOT_POLICY)
                .append("' then (select t.org_id from domain_roots t where t.root_domain=(a.detail->>'rootDomain')) end");
        return sql.append(" end)").toString();
    }

    record Target(long id, String name) { }
    private record Binding(String from, String orgId, boolean legacyNumeric) {
        Binding(String from, String orgId) { this(from, orgId, false); }
    }

    private static String legacyIdSql(String expression) {
        return "(case when " + expression + " ~ '^[1-9][0-9]{0,18}$' then case when (" + expression
                + ")::numeric <= 9223372036854775807 then (" + expression + ")::bigint end end)";
    }

    private static String uuidSql(String expression) {
        return "(case when " + expression + " ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'"
                + " then (" + expression + ")::uuid end)";
    }
}
