package kr.ac.pusan.pickle.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

class DevEnvironmentGuardTest {

    private final DevEnvironmentGuard guard = new DevEnvironmentGuard((url, user, password) -> {});

    @Test
    void bootRegistersTheGuardBeforeStartingTheApplication() {
        assertThat(SpringFactoriesLoader.loadFactories(EnvironmentPostProcessor.class,
                getClass().getClassLoader()))
                .anyMatch(DevEnvironmentGuard.class::isInstance);
    }

    @Test
    void acceptsMockOnlyDevelopmentOnAnExplicitLocalDatabase() {
        assertThatCode(() -> check(dev())).doesNotThrowAnyException();
    }

    @Test
    void acceptsOnlyTheDedicatedRemoteDatabaseWithVerifiedTls() {
        assertThatCode(() -> check(remote().withProperty("spring.datasource.url",
                "jdbc:postgresql://192.0.2.60:5432/pickle_dev"
                        + "?sslmode=verify-full&sslrootcert=/etc/pickle/dev-db-ca.pem")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> check(remote().withProperty("spring.datasource.url",
                "jdbc:postgresql://192.0.2.60:5432/pickle_dev?sslmode=require")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("verify-full");
        assertThatThrownBy(() -> check(remote().withProperty("spring.datasource.url",
                "jdbc:postgresql://192.0.2.60:5432/pickle_dev"
                        + "?sslmode=verify-full&sslmode=disable"
                        + "&sslrootcert=/etc/pickle/dev-db-ca.pem")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JDBC query");
    }

    @Test
    void rejectsProductionDatabaseBeforeFlywayRuns() {
        assertThatThrownBy(() -> check(dev().withProperty("spring.datasource.url",
                "jdbc:postgresql://198.51.100.21:5432/pickle_prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PICKLE_DEV_DB_HOST");
    }

    @Test
    void rejectsAnExternalProxyWithoutLiveOptIn() {
        assertThatThrownBy(() -> check(dev()
                .withProperty("pickle.proxy-agent.base-url", "https://external.example.com")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("proxy-agent.base-url");
    }

    @Test
    void rejectsRealDnsAndUnplannedCredentials() {
        assertThatThrownBy(() -> check(dev().withProperty("pickle.dns.provider", "google")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DNS provider");
        assertThatThrownBy(() -> check(dev().withProperty("pickle.proxmox.token-id", "dev@pve!api")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("REAL_INTEGRATIONS");
    }

    @Test
    void requiresAnIsolatedPoolWhenRealProxmoxIsEnabled() {
        assertThatThrownBy(() -> check(dev()
                .withProperty("PICKLE_DEV_REAL_INTEGRATIONS_ENABLED", "true")
                .withProperty("pickle.proxmox.token-id", "dev@pve!api")
                .withProperty("pickle.proxmox.token-secret", "separate-dev-token")
                .withProperty("pickle.dev-inventory.node-api-host", "https://dev-pve:8006")
                .withProperty("PICKLE_DEV_NODE_API_HOST", "https://dev-pve:8006")
                .withProperty("PICKLE_DEV_IP_POOL_CIDR", "192.0.2.0/24")
                .withProperty("PICKLE_DEV_IP_POOL_RESERVED_RANGES", "[]")
                .withProperty("PICKLE_DEV_TEMPLATE_VMID", "9001")
                .withProperty("PICKLE_DEV_VMID_START", "900000000")
                .withProperty("pickle.dev-inventory.ip-pool-cidr", "192.0.2.0/24")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("isolated IP pool");
    }

    @Test
    void leavesTheProductionProfileUntouched() {
        MockEnvironment production = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://198.51.100.21:5432/pickle_prod")
                .withProperty("pickle.dns.provider", "google");
        production.setActiveProfiles("prod");
        assertThatCode(() -> check(production)).doesNotThrowAnyException();
    }

    @Test
    void rejectsEffectiveDatasourceAndFlywayOverridesBeforeInspection() {
        assertThatThrownBy(() -> check(dev().withProperty("PICKLE_DB_URL",
                "jdbc:postgresql://127.0.0.1:5432/pickle_dev")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://198.51.100.21:5432/pickle_prod")))
                .isInstanceOf(IllegalStateException.class);
        for (String key : java.util.List.of("spring.flyway.url", "spring.flyway.user",
                "spring.flyway.password", "spring.datasource.hikari.jdbc-url",
                "spring.datasource.hikari.data-source-properties.PGHOST")) {
            assertThatThrownBy(() -> check(dev().withProperty(key, "unvalidated")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsRoutingAndTlsOverridesInJdbcQuery() {
        for (String parameter : java.util.List.of("PGHOST=198.51.100.21", "PGDBNAME=pickle_prod",
                "sslfactory=org.postgresql.ssl.NonValidatingFactory", "sslmode=disable&sslmode=require")) {
            assertThatThrownBy(() -> check(dev().withProperty("spring.datasource.url",
                    "jdbc:postgresql://127.0.0.1:5432/pickle_dev?" + parameter)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void usesSpringBooleanConversionForEffectiveWorkerAndAutomationFlags() {
        for (String key : java.util.List.of("jobrunr.background-job-server.enabled",
                "pickle.network-policy.enabled", "pickle.vm-firewall.enabled",
                "pickle.relay-retirement.enabled", "pickle.llm.usage-rollup-direct.enabled",
                "pickle.llm.quota-direct.enabled", "pickle.openrouter.direct-polling.enabled")) {
            for (String value : java.util.List.of("true", "on", "yes", "1")) {
                assertThatThrownBy(() -> check(dev().withProperty(key, value)))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    void rejectsEffectiveCredentialsAndMailEvenWithoutPickleEnvironmentAliases() {
        for (String key : java.util.List.of("pickle.proxmox.token-id", "pickle.oauth.google.client-secret",
                "spring.mail.host", "spring.mail.password")) {
            assertThatThrownBy(() -> check(dev().withProperty(key, "secret-that-must-not-appear")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("secret-that-must-not-appear");
        }
    }

    @Test
    void guardsDevelopmentWhenItIsTheDefaultRatherThanAnActiveProfile() {
        MockEnvironment defaults = dev().withProperty("spring.datasource.url",
                "jdbc:postgresql://198.51.100.21:5432/pickle_prod");
        defaults.setActiveProfiles();
        defaults.setDefaultProfiles("dev");
        assertThatThrownBy(() -> check(defaults)).isInstanceOf(IllegalStateException.class);
        defaults.setDefaultProfiles("dev", "prod");
        assertThatThrownBy(() -> check(defaults)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only active profile");
    }

    @Test
    void allowsInternalLinkTokensPairedWithMockEndpoints() {
        assertThatCode(() -> check(dev().withProperty("pickle.proxy-agent.base-url", "http://127.0.0.1:9443")
                .withProperty("pickle.proxy-agent.token", "separate-dev-token")
                .withProperty("pickle.terminal.bridge-control-token", "separate-dev-token")
                .withProperty("pickle.llm-gateway.token", "separate-dev-token")
                .withProperty("pickle.sshgw.token", "separate-dev-token")))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesArbitraryExternalMockEndpointsAndAddresses() {
        for (String key : java.util.List.of("pickle.proxy-agent.base-url",
                "pickle.terminal.bridge-control-base-url", "pickle.openrouter.base-url")) {
            assertThatThrownBy(() -> check(dev().withProperty(key, "https://external.example.com")))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> check(dev().withProperty("pickle.llm-gateway.allowed-source-ip", "10.0.0.1")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void usesRelaxedEnvironmentBindingForAutomationOverrides() {
        MockEnvironment environment = dev();
        environment.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
                "systemEnvironment", java.util.Map.of("JOBRUNR_BACKGROUNDJOBSERVER_ENABLED", "on")));
        assertThatThrownBy(() -> check(environment)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REAL_INTEGRATIONS");
    }

    @Test
    void refusesRemoteDatabaseIdentityMismatches() {
        for (String key : java.util.List.of("PICKLE_DEV_DB_HOST", "PICKLE_DEV_DB_NAME", "PICKLE_DEV_DB_USER")) {
            assertThatThrownBy(() -> check(remote().withProperty("spring.datasource.url",
                    "jdbc:postgresql://192.0.2.60:5432/pickle_dev"
                            + "?sslmode=verify-full&sslrootcert=/etc/pickle/dev-db-ca.pem")
                    .withProperty(key, "different")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void keepsSyntheticPublishingUnderReservedRootDomains() {
        for (String root : java.util.List.of("dev.example.test", "publishing.invalid")) {
            assertThatCode(() -> check(dev().withProperty("pickle.dev-inventory.root-domain", root)))
                    .doesNotThrowAnyException();
        }
        for (String root : java.util.List.of("example.com", "dev.test.example.com", "dev.test/path", "dev.test\"")) {
            assertThatThrownBy(() -> check(dev().withProperty("pickle.dev-inventory.root-domain", root)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("reserved");
        }
    }

    private MockEnvironment remote() {
        return dev().withProperty("PICKLE_DEV_DB_HOST", "192.0.2.60")
                .withProperty("PICKLE_DEV_DB_NAME", "pickle_dev")
                .withProperty("PICKLE_DEV_DB_USER", "pickle_dev_api");
    }

    private MockEnvironment dev() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/pickle_development")
                .withProperty("spring.datasource.username", "pickle_dev_api")
                .withProperty("pickle.dev-inventory.root-domain", "dev.pickle.test")
                .withProperty("pickle.dns.provider", "noop");
        environment.setActiveProfiles("dev");
        return environment;
    }

    private void check(MockEnvironment environment) {
        guard.postProcessEnvironment(environment, null);
    }
}
