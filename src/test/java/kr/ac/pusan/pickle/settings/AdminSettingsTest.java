package kr.ac.pusan.pickle.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /admin/settings} + {@code PUT /admin/settings/{key}} per contract
 * v0.5.0: SYS_ADMIN only, static whitelist (unknown/non-whitelisted/unseeded
 * keys 404 without existence leaks), per-key type/range validation (422 with
 * field errors), and the {@code setting.update} audit row with old/new values.
 * Mutated keys are restored afterwards — the embedded database is shared with
 * the other test classes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminSettingsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String sysAdminToken;
    private String orgAdminToken;
    private String graceHoursBackup;
    private String sshGatewayBackup;

    @BeforeEach
    void setUp() {
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow());
        graceHoursBackup = readValue("vm_delete_grace_hours");
        sshGatewayBackup = readValue("ssh_gateway_enabled");
    }

    @AfterEach
    void restore() {
        writeValue("vm_delete_grace_hours", graceHoursBackup);
        writeValue("ssh_gateway_enabled", sshGatewayBackup);
        jdbcTemplate.update("delete from settings where key = 'adset_internal_probe'");
    }

    @Test
    void settingsAreSysAdminOnly() throws Exception {
        mockMvc.perform(get("/api/v1/admin/settings")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(put("/api/v1/admin/settings/ssh_gateway_enabled")
                        .header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listReturnsEveryRowWithWhitelistDrivenEditability() throws Exception {
        jdbcTemplate.update("""
                insert into settings (key, value, description)
                values ('adset_internal_probe', '"internal"'::jsonb, '내부 전용')
                on conflict (key) do nothing
                """);
        mockMvc.perform(get("/api/v1/admin/settings")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='vm_delete_grace_hours' && @.valueType=='INTEGER'"
                        + " && @.editable==true)]").exists())
                .andExpect(jsonPath("$[?(@.key=='ssh_gateway_enabled' && @.valueType=='BOOLEAN'"
                        + " && @.editable==true)]").exists())
                .andExpect(jsonPath("$[?(@.key=='allowed_root_domains' && @.valueType=='JSON'"
                        + " && @.editable==true)]").exists())
                // present in DB but not whitelisted → visible, read-only
                .andExpect(jsonPath("$[?(@.key=='adset_internal_probe' && @.valueType=='STRING'"
                        + " && @.editable==false)]").exists());
    }

    @Test
    void putUpdatesValueAndWritesTheAuditRow() throws Exception {
        mockMvc.perform(put("/api/v1/admin/settings/vm_delete_grace_hours")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("vm_delete_grace_hours"))
                .andExpect(jsonPath("$.value").value(200))
                .andExpect(jsonPath("$.valueType").value("INTEGER"))
                .andExpect(jsonPath("$.editable").value(true));
        assertThat(readValue("vm_delete_grace_hours")).isEqualTo("200");

        Long sysAdminId = userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).map(User::getId).orElseThrow();
        Integer audits = jdbcTemplate.queryForObject("""
                select count(*) from audit_logs
                 where action = 'setting.update' and actor_id = ?
                   and detail ->> 'key' = 'vm_delete_grace_hours'
                   and detail ->> 'new' = '200'
                """, Integer.class, sysAdminId);
        assertThat(audits).isGreaterThanOrEqualTo(1);

        // booleans round-trip too (the SSH gateway kill switch)
        mockMvc.perform(put("/api/v1/admin/settings/ssh_gateway_enabled")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(true));
        assertThat(readValue("ssh_gateway_enabled")).isEqualTo("true");
    }

    @Test
    void putMasksUnknownNonWhitelistedAndUnseededKeysAs404() throws Exception {
        // wholly unknown key
        mockMvc.perform(put("/api/v1/admin/settings/no_such_key")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // exists in the DB but is not whitelisted → indistinguishable 404
        jdbcTemplate.update("""
                insert into settings (key, value, description)
                values ('adset_internal_probe', '1'::jsonb, '내부 전용')
                on conflict (key) do nothing
                """);
        mockMvc.perform(put("/api/v1/admin/settings/adset_internal_probe")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 2}"))
                .andExpect(status().isNotFound());

        // whitelisted AND seeded since V18 → editable like any other key
        mockMvc.perform(put("/api/v1/admin/settings/vm_expiry_autostop_enabled")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("vm_expiry_autostop_enabled"))
                .andExpect(jsonPath("$.value").value(true));
    }

    @Test
    void putRejectsTypeAndRangeViolationsAs422() throws Exception {
        // wrong JSON type for an INTEGER key
        putExpecting422("vm_delete_grace_hours", "\"abc\"");
        // integer out of range (1–2160)
        putExpecting422("vm_delete_grace_hours", "0");
        putExpecting422("vm_delete_grace_hours", "2161");
        // boolean key rejects strings
        putExpecting422("ssh_gateway_enabled", "\"yes\"");
        // number in (0, 10]
        putExpecting422("vcpu_overcommit_warn", "0");
        putExpecting422("vcpu_overcommit_warn", "10.5");
        // hostname array: uppercase, non-array, and non-string entries
        putExpecting422("allowed_root_domains", "[\"UPPER.example\"]");
        putExpecting422("allowed_root_domains", "\"not-an-array\"");
        putExpecting422("reserved_subdomains", "[\"ok\", 5]");
        // value must be present (JSON null is not a value)
        putExpecting422("ssh_gateway_enabled", "null");
        // the IP quarantine window may never drop below the relay agent's
        // snapshot replay window: a shorter one lets a released address be
        // reassigned while stale forwarding rules still point at it
        putExpecting422("ip_quarantine_hours", "0");
        putExpecting422("ip_quarantine_hours", "23");
        mockMvc.perform(put("/api/v1/admin/settings/ip_quarantine_hours")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 24}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(24));

        assertThat(readValue("vm_delete_grace_hours")).isEqualTo(graceHoursBackup);
    }

    private void putExpecting422(String key, String valueJson) throws Exception {
        mockMvc.perform(put("/api/v1/admin/settings/" + key)
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": " + valueJson + "}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").exists());
    }

    private String readValue(String key) {
        return jdbcTemplate.queryForObject("select value from settings where key = ?",
                String.class, key);
    }

    private void writeValue(String key, String valueJson) {
        jdbcTemplate.update("update settings set value = ?::jsonb where key = ?", valueJson, key);
    }
}
