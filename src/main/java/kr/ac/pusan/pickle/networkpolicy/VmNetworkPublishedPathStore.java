package kr.ac.pusan.pickle.networkpolicy;

import java.util.LinkedHashSet;
import java.util.List;
import kr.ac.pusan.pickle.config.VmFirewallPolicyProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads desired live publications and expands only configured backend sources. */
@Repository
public class VmNetworkPublishedPathStore {

    private final JdbcTemplate jdbc;
    private final VmFirewallPolicyProperties properties;

    public VmNetworkPublishedPathStore(JdbcTemplate jdbc,
            VmFirewallPolicyProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public void requireNoLegacyForInitialOptIn(long vmId) {
        Boolean legacy = jdbc.queryForObject("""
                select exists(
                    select 1 from routes r join domains d on d.id = r.domain_id
                     where d.vm_id = ? and r.status <> 'REMOVED')
                    or exists(
                    select 1 from port_mappings
                     where vm_id = ? and delivery_state = 'LEGACY'
                       and status <> 'REMOVING')
                """, Boolean.class, vmId, vmId);
        if (Boolean.TRUE.equals(legacy)) {
            throw VmNetworkPolicyStore.unavailable(
                    "기존 flow 부재가 입증되지 않은 legacy 공개 경로가 있어 VM 정책 opt-in을 거부했습니다.");
        }
    }

    public List<VmNetworkPolicyCompiler.PublishedPath> find(long vmId) {
        LinkedHashSet<VmNetworkPolicyCompiler.PublishedPath> paths = new LinkedHashSet<>();
        Boolean managed = jdbc.queryForObject(
                "select exists(select 1 from vm_network_policies where vm_id = ?)",
                Boolean.class, vmId);
        if (Boolean.TRUE.equals(managed)) {
            jdbc.query("""
                    select source_kind::text, protocol, target_port
                      from vm_network_derived_paths where vm_id = ?
                     order by source_kind, protocol, target_port, id
                    """, (rs, row) -> {
                List<String> sources = "PROXY".equals(rs.getString(1))
                        ? properties.proxySourceIps() : properties.relaySourceIps();
                VmNetworkRule.Protocol protocol =
                        VmNetworkRule.Protocol.valueOf(rs.getString(2));
                int port = rs.getInt(3);
                sources.forEach(source -> paths.add(new VmNetworkPolicyCompiler.PublishedPath(
                        source, protocol, port)));
                return row;
            }, vmId);
            return List.copyOf(paths);
        }
        List<Integer> routePorts = jdbc.queryForList("""
                select distinct r.target_port
                  from routes r join domains d on d.id = r.domain_id
                 where d.vm_id = ? and r.status <> 'REMOVED'
                 order by r.target_port
                """, Integer.class, vmId);
        for (int port : routePorts) {
            for (String source : properties.proxySourceIps()) {
                paths.add(new VmNetworkPolicyCompiler.PublishedPath(
                        source, VmNetworkRule.Protocol.TCP, port));
            }
        }
        List<Forwarding> forwardings = jdbc.query("""
                select distinct proto, target_port from port_mappings
                 where vm_id = ? and status = 'ACTIVE'
                 order by proto, target_port
                """, (rs, row) -> new Forwarding(
                        VmNetworkRule.Protocol.valueOf(rs.getString(1)), rs.getInt(2)), vmId);
        for (Forwarding forwarding : forwardings) {
            for (String source : properties.relaySourceIps()) {
                paths.add(new VmNetworkPolicyCompiler.PublishedPath(
                        source, forwarding.protocol(), forwarding.port()));
            }
        }
        return List.copyOf(paths);
    }

    private record Forwarding(VmNetworkRule.Protocol protocol, int port) {
    }
}
