package kr.ac.pusan.recoverypolicy;

import kr.ac.pusan.pickle.config.VmFirewallPolicyProperties;
import kr.ac.pusan.pickle.networkpolicy.RecoveryPolicyCommand;
import kr.ac.pusan.pickle.networkpolicy.RecoveryPolicyDb;
import kr.ac.pusan.pickle.networkpolicy.RecoveryProviderVerifier;
import kr.ac.pusan.pickle.networkpolicy.VmFirewallBarrierReconciler;
import kr.ac.pusan.pickle.networkpolicy.VmNetworkPolicyAdvisoryLock;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.proxmox.ProxmoxProperties;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import javax.sql.DataSource;

/** Explicit, non-scanning context: no web, worker, recurring producer, seeder or Flyway. */
@SpringBootConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration(DataSourceAutoConfiguration.class)
@EnableConfigurationProperties({VmFirewallPolicyProperties.class, ProxmoxProperties.class})
public class RecoveryPolicyApplication {

    @Bean
    VmNetworkPolicyAdvisoryLock policyLock(DataSource dataSource) {
        return new VmNetworkPolicyAdvisoryLock(dataSource);
    }

    @Bean
    ProxmoxClient proxmoxClient(ProxmoxProperties properties) {
        return new ProxmoxClient(properties);
    }

    @Bean
    VmFirewallBarrierReconciler reconciler(ProxmoxClient proxmox,
            VmFirewallPolicyProperties properties) {
        return new VmFirewallBarrierReconciler(proxmox, properties);
    }

    @Bean
    RecoveryProviderVerifier providerVerifier(ProxmoxClient proxmox) {
        return new RecoveryProviderVerifier(proxmox);
    }

    @Bean
    RecoveryPolicyDb recoveryPolicyDb(VmFirewallPolicyProperties properties) {
        return new RecoveryPolicyDb(properties);
    }

    @Bean
    RecoveryPolicyCommand recoveryPolicyCommand(DataSource dataSource,
            VmNetworkPolicyAdvisoryLock lock, RecoveryPolicyDb database,
            RecoveryProviderVerifier provider, VmFirewallBarrierReconciler reconciler,
            VmFirewallPolicyProperties properties) {
        return new RecoveryPolicyCommand(dataSource, lock, database, provider,
                reconciler, properties);
    }
}
