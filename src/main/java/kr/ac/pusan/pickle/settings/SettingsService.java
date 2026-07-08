package kr.ac.pusan.pickle.settings;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read access to the runtime-tunable {@code settings} store (V1 baseline).
 * Values are jsonb; reference rows are seeded by V3 and operator-editable.
 */
@Service
public class SettingsService {

    public static final String ALLOWED_ROOT_DOMAINS = "allowed_root_domains";
    public static final String RESERVED_SUBDOMAINS = "reserved_subdomains";
    public static final String VCPU_OVERCOMMIT_WARN = "vcpu_overcommit_warn";
    public static final String MEMORY_USAGE_WARN = "memory_usage_warn";
    public static final String IP_QUARANTINE_HOURS = "ip_quarantine_hours";
    public static final String VM_DELETE_GRACE_HOURS = "vm_delete_grace_hours";
    public static final String ADMIN_DELETE_MIN_NOTICE_DAYS = "admin_delete_min_notice_days";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SettingsService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** The setting as a list of strings; empty when the key is missing. */
    public List<String> stringList(String key) {
        JsonNode node = read(key);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(node.size());
        node.forEach(item -> values.add(item.asString()));
        return List.copyOf(values);
    }

    /** The setting as a decimal number, or {@code fallback} when missing. */
    public double decimal(String key, double fallback) {
        JsonNode node = read(key);
        return node != null && node.isNumber() ? node.asDouble() : fallback;
    }

    /** The setting as an integer, or {@code fallback} when missing. */
    public int integer(String key, int fallback) {
        JsonNode node = read(key);
        return node != null && node.isNumber() ? node.asInt() : fallback;
    }

    private JsonNode read(String key) {
        String json = jdbcTemplate.query("select value from settings where key = ?",
                rs -> rs.next() ? rs.getString(1) : null, key);
        return json == null ? null : objectMapper.readTree(json);
    }
}
