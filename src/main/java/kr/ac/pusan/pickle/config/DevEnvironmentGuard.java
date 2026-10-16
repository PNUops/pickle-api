package kr.ac.pusan.pickle.config;

import java.net.URI;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/** Validate development inputs and database provenance before context creation. */
public class DevEnvironmentGuard implements EnvironmentPostProcessor {

    private static final Pattern JDBC = Pattern.compile(
            "^jdbc:postgresql://(?<host>\\[[^\\]]+\\]|[a-zA-Z0-9.-]+)"
                    + ":(?<port>[0-9]+)/(?<database>[a-zA-Z0-9_]+)(?:\\?(?<query>.*))?$");
    private static final List<String> MOCK_URLS = List.of(
            "pickle.proxy-agent.base-url", "pickle.terminal.bridge-control-base-url",
            "pickle.openrouter.base-url", "pickle.dev-inventory.node-api-host");
    private static final List<String> MOCK_ADDRESSES = List.of(
            "pickle.llm-gateway.allowed-source-ip", "pickle.sshgw.allowed-source-ip",
            "pickle.relay.restricted-source-ips", "pickle.publishing.proxy-public-ip",
            "pickle.dev-inventory.relay-source-ip", "pickle.dev-inventory.ip-pool-cidr",
            "pickle.dev-inventory.ip-pool-gateway", "pickle.dev-inventory.ip-pool-dns");
    private static final List<String> EXTERNAL_CREDENTIALS = List.of(
            "pickle.proxmox.token-id", "pickle.proxmox.token-secret",
            "pickle.oauth.google.client-id", "pickle.oauth.google.client-secret");
    private final DatabaseInspection inspection;

    public DevEnvironmentGuard() {
        this(DevEnvironmentGuard::inspectDatabase);
    }

    DevEnvironmentGuard(DatabaseInspection inspection) {
        this.inspection = inspection;
    }

    @FunctionalInterface
    interface DatabaseInspection {
        void inspect(String url, String username, String password);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
            SpringApplication application) {
        String[] profiles = environment.getActiveProfiles().length == 0
                ? environment.getDefaultProfiles() : environment.getActiveProfiles();
        if (!Arrays.asList(profiles).contains("dev")) {
            return;
        }
        if (profiles.length != 1) {
            refuse("dev must be the only active profile");
        }
        Binder binder = Binder.get(environment);
        for (String key : List.of("spring.flyway.url", "spring.flyway.user",
                "spring.flyway.password", "spring.datasource.jndi-name",
                "spring.datasource.hikari.jdbc-url", "spring.datasource.hikari.username",
                "spring.datasource.hikari.password", "spring.datasource.hikari.data-source-class-name",
                "spring.datasource.hikari.data-source-jndi", "spring.datasource.hikari.driver-class-name")) {
            if (present(environment, key)) {
                refuse(key + " must not override the validated development datasource");
            }
        }
        for (String key : List.of("spring.datasource.hikari.data-source-properties",
                "spring.flyway.jdbc-properties")) {
            if (!binder.bind(key, Bindable.mapOf(String.class, String.class)).orElse(Map.of()).isEmpty()) {
                refuse("dev must not supply additional JDBC driver properties");
            }
        }
        if (!binder.bind("spring.flyway.enabled", Boolean.class).orElse(true)) {
            refuse("dev Flyway must be enabled");
        }
        String type = property(environment, "spring.datasource.type");
        if (!type.isBlank() && !"com.zaxxer.hikari.HikariDataSource".equals(type)) {
            refuse("dev must use the validated Hikari datasource");
        }
        String driver = property(environment, "spring.datasource.driver-class-name");
        if (!driver.isBlank() && !"org.postgresql.Driver".equals(driver)) {
            refuse("dev must use the PostgreSQL JDBC driver");
        }
        String dbUrl = property(environment, "spring.datasource.url");
        String dbUser = property(environment, "spring.datasource.username");
        validateDatabase(environment, dbUrl, dbUser);
        if (!"noop".equalsIgnoreCase(property(environment, "pickle.dns.provider"))) {
            refuse("dev DNS provider must be noop");
        }
        for (String key : List.of("spring.mail.host", "spring.mail.username", "spring.mail.password",
                "pickle.mail.from", "PICKLE_SMTP_HOST", "PICKLE_SMTP_USERNAME",
                "PICKLE_SMTP_PASSWORD", "PICKLE_MAIL_FROM")) {
            if (present(environment, key)) {
                refuse(key + " must not be supplied to the mock-mail profile");
            }
        }
        if (enabled(environment, "pickle.openrouter.direct-polling.enabled")) {
            refuse("dev must not poll the OpenRouter account");
        }
        boolean live = enabled(environment, "PICKLE_DEV_REAL_INTEGRATIONS_ENABLED");
        if (!live) {
            String root = property(environment, "pickle.dev-inventory.root-domain");
            if (root.length() > 253 || !root.matches(
                    "(?i)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:test|invalid)")) {
                refuse("development root domain must use a reserved .test or .invalid domain");
            }
            for (String key : MOCK_URLS) {
                if (!mockUrl(property(environment, key))) {
                    refuse(key + " must use a loopback or .invalid mock endpoint");
                }
            }
            for (String key : MOCK_ADDRESSES) {
                if (!mockAddresses(property(environment, key))) {
                    refuse(key + " must use loopback or documentation addresses");
                }
            }
            String relay = property(environment, "pickle.dev-inventory.relay-public-host");
            if (!relay.isBlank() && !mockUrl("http://" + relay)) {
                refuse("development relay must use a mock host");
            }
        }
        boolean credentials = EXTERNAL_CREDENTIALS.stream().anyMatch(key -> present(environment, key));
        boolean automation = List.of("jobrunr.background-job-server.enabled",
                "pickle.network-policy.enabled", "pickle.vm-firewall.enabled",
                "pickle.relay-retirement.enabled", "pickle.llm.usage-rollup-direct.enabled",
                "pickle.llm.quota-direct.enabled")
                .stream().anyMatch(key -> enabled(environment, key));
        if (!live && (credentials || automation)) {
            refuse("dev external credentials and automation require PICKLE_DEV_REAL_INTEGRATIONS_ENABLED=true");
        }
        if (present(environment, "pickle.proxmox.token-id")
                || present(environment, "pickle.proxmox.token-secret")) {
            if (!present(environment, "pickle.proxmox.token-id")
                    || !present(environment, "pickle.proxmox.token-secret")) {
                refuse("dev Proxmox requires its own token ID and secret together");
            }
            for (String key : List.of("PICKLE_DEV_NODE_API_HOST", "PICKLE_DEV_IP_POOL_CIDR",
                    "PICKLE_DEV_IP_POOL_RESERVED_RANGES", "PICKLE_DEV_TEMPLATE_VMID", "PICKLE_DEV_VMID_START")) {
                if (!present(environment, key)) {
                    refuse("dev Proxmox requires explicit isolated inventory: " + key);
                }
            }
            String ranges = property(environment, "pickle.dev-inventory.ip-pool-reserved-ranges");
            if (mockUrl(property(environment, "pickle.dev-inventory.node-api-host"))
                    || "[]".equals(ranges.replaceAll("\\s", "")) || ranges.isBlank()) {
                refuse("dev Proxmox requires an explicit isolated IP pool and reserved ranges");
            }
        }
        inspection.inspect(dbUrl, dbUser, property(environment, "spring.datasource.password"));
    }

    private static void validateDatabase(ConfigurableEnvironment environment, String url, String user) {
        var database = JDBC.matcher(url);
        if (!database.matches()) {
            refuse("spring.datasource.url must explicitly select a development database");
        }
        Map<String, String> query = new HashMap<>();
        String parameters = database.group("query");
        for (String parameter : parameters == null || parameters.isEmpty()
                ? new String[0] : parameters.split("&", -1)) {
            String[] pair = parameter.split("=", 2);
            if (pair.length != 2 || !Set.of("sslmode", "sslrootcert").contains(pair[0])
                    || pair[1].isBlank() || pair[1].contains("%") || query.putIfAbsent(pair[0], pair[1]) != null) {
                refuse("dev JDBC query only permits unique sslmode and sslrootcert parameters");
            }
        }
        String host = database.group("host").replace("[", "").replace("]", "");
        String expectedHost = property(environment, "PICKLE_DEV_DB_HOST");
        String expectedName = property(environment, "PICKLE_DEV_DB_NAME");
        String expectedUser = property(environment, "PICKLE_DEV_DB_USER");
        String expectedPort = property(environment, "PICKLE_DEV_DB_PORT");
        if (expectedHost.isBlank()) {
            if (!loopback(host)) {
                refuse("remote dev database requires explicit PICKLE_DEV_DB_HOST, NAME and USER");
            }
        } else if (!expectedHost.equalsIgnoreCase(host)) {
            refuse("effective datasource host differs from PICKLE_DEV_DB_HOST");
        }
        if ((!expectedName.isBlank() && !expectedName.equals(database.group("database")))
                || (!expectedUser.isBlank() && !expectedUser.equals(user))
                || (!expectedPort.isBlank() && !expectedPort.equals(database.group("port")))) {
            refuse("effective datasource differs from the expected development database identity");
        }
        if (!loopback(host) && (expectedName.isBlank() || expectedUser.isBlank()
                || !"verify-full".equals(query.get("sslmode"))
                || !query.getOrDefault("sslrootcert", "").startsWith("/"))) {
            refuse("remote dev database requires an explicit name, user and verify-full TLS");
        }
    }

    private static boolean mockUrl(String value) {
        if (value.isBlank()) {
            return true;
        }
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return host != null && uri.getUserInfo() == null
                    && Set.of("http", "https").contains(uri.getScheme())
                    && (loopback(host) || host.toLowerCase(java.util.Locale.ROOT).endsWith(".invalid"));
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static boolean loopback(String host) {
        return Set.of("localhost", "127.0.0.1", "::1", "[::1]")
                .contains(host.toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean mockAddresses(String value) {
        if (value.isBlank()) {
            return true;
        }
        for (String address : value.split(",", -1)) {
            String[] parts = address.trim().split("/", -1);
            String host = parts[0];
            if (parts.length > 2 || (!loopback(host)
                    && !host.matches("(?:192\\.0\\.2|198\\.51\\.100|203\\.0\\.113)\\.[0-9]{1,3}"))) {
                return false;
            }
            if (!loopback(host) && Integer.parseInt(host.substring(host.lastIndexOf('.') + 1)) > 255) {
                return false;
            }
            if (parts.length == 2) {
                try {
                    int prefix = Integer.parseInt(parts[1]);
                    if (prefix < (host.contains(":") ? 128 : 24) || prefix > (host.contains(":") ? 128 : 32)) {
                        return false;
                    }
                } catch (NumberFormatException failure) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean enabled(ConfigurableEnvironment environment, String key) {
        return key.startsWith("PICKLE_") ? environment.getProperty(key, Boolean.class, false)
                : Binder.get(environment).bind(key, Boolean.class).orElse(false);
    }

    private static String property(ConfigurableEnvironment environment, String key) {
        return key.startsWith("PICKLE_") ? environment.getProperty(key, "")
                : Binder.get(environment).bind(key, String.class).orElse("");
    }

    /** Read only, before context creation: reject any existing unmarked user schema. */
    static void inspectDatabase(String url, String username, String password) {
        Properties credentials = new Properties();
        credentials.setProperty("user", username);
        credentials.setProperty("password", password);
        credentials.setProperty("connectTimeout", "5");
        credentials.setProperty("socketTimeout", "5");
        try (var connection = DriverManager.getConnection(url, credentials)) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (var tables = statement.executeQuery("""
                        select exists (select 1 from pg_class c join pg_namespace n
                          on n.oid = c.relnamespace where c.relkind in ('r','p','v','m','S','f')
                          and n.nspname not in ('pg_catalog','information_schema')
                          and n.nspname not like 'pg_toast%'),
                          to_regclass('public.settings') is not null
                        """)) {
                    tables.next();
                    if (!tables.getBoolean(1)) {
                        return;
                    }
                    if (!tables.getBoolean(2)) {
                        refuse("existing database has no development marker");
                    }
                }
                try (var marker = statement.executeQuery("""
                        select exists (select 1 from public.settings
                          where key = 'dev_environment_marker' and value = 'true'::jsonb)
                        """)) {
                    marker.next();
                    if (!marker.getBoolean(1)) {
                        refuse("existing database has no development marker");
                    }
                }
            }
        } catch (SQLException failure) {
            // Do not retain the driver exception: it may contain connection secrets.
            refuse("development database inspection failed");
        }
    }

    private static boolean present(ConfigurableEnvironment environment, String key) {
        return !property(environment, key).isBlank();
    }

    private static void refuse(String message) {
        throw new IllegalStateException("Unsafe dev environment: " + message);
    }
}
