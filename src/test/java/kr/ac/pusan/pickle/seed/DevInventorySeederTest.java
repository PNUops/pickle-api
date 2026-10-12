package kr.ac.pusan.pickle.seed;

import static org.assertj.core.api.Assertions.assertThat;

import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** A new database must use the selected inventory and VMID band at first boot. */
@SpringBootTest(properties = {
    "pickle.dev-inventory.root-domain=dev.example.test",
    "pickle.dev-inventory.node-name=dev-fixture",
    "pickle.dev-inventory.node-api-host=https://dev-fixture.invalid:8006",
    "pickle.dev-inventory.node-cpu-threads=8",
    "pickle.dev-inventory.node-memory-mb=8192",
    "pickle.dev-inventory.node-vm-bridge=vmbr-dev",
    "pickle.dev-inventory.node-storage=dev-storage",
    "pickle.dev-inventory.ip-pool-name=dev-fixture-pool",
    "pickle.dev-inventory.ip-pool-cidr=198.19.0.0/16",
    "pickle.dev-inventory.ip-pool-gateway=198.19.0.1",
    "pickle.dev-inventory.ip-pool-dns=198.19.0.53",
    "pickle.dev-inventory.ip-pool-reserved-ranges=[]",
    "pickle.dev-inventory.template-vmid=9001",
    "pickle.dev-inventory.vmid-start=900000000",
    "pickle.dev-inventory.relay-name=dev-fixture-relay",
    "pickle.dev-inventory.relay-source-ip=192.0.2.60",
    "pickle.dev-inventory.relay-public-host=relay-fixture.invalid",
    "pickle.dev-inventory.relay-port-start=30000",
    "pickle.dev-inventory.relay-port-end=30999"
})
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class DevInventorySeederTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void firstBootUsesOnlyTheConfiguredInventoryAndSequence() {
        assertThat(jdbcTemplate.queryForObject("select api_host from nodes where name = ?",
                String.class, "dev-fixture")).isEqualTo("https://dev-fixture.invalid:8006");
        assertThat(jdbcTemplate.queryForObject("select cidr from ip_pools where name = ?",
                String.class, "dev-fixture-pool")).isEqualTo("198.19.0.0/16");
        assertThat(jdbcTemplate.queryForObject("select source_ip from relays where name = ?",
                String.class, "dev-fixture-relay")).isEqualTo("192.0.2.60");
        assertThat(jdbcTemplate.queryForObject(
                "select value->>0 from settings where key = 'allowed_root_domains'",
                String.class)).isEqualTo("dev.example.test");
        assertThat(jdbcTemplate.queryForObject("select proxmox_vmid from os_images",
                Integer.class)).isEqualTo(9001);
        assertThat(jdbcTemplate.queryForObject("select nextval('vmid_seq')", Long.class))
                .isEqualTo(900000000L);
    }
}
