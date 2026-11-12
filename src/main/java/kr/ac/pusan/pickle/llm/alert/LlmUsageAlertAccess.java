package kr.ac.pusan.pickle.llm.alert;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The ordinary usage route requires both current membership and a resource grant. */
@Component
public class LlmUsageAlertAccess {
    public static final String EVENT = "llm.usage.threshold";
    private final JdbcTemplate jdbc;

    public LlmUsageAlertAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static String keyRead(String keyAlias, String userSql) {
        return "exists(select 1 from users au where au.id=" + userSql + " and au.status='ACTIVE')"
                + " and exists(select 1 from workspace_members am where am.workspace_id=" + keyAlias
                + ".workspace_id and am.user_id=" + userSql + ")"
                + " and exists(select 1 from resource_access_grants ag where ag.resource_type='LLM_API_KEY'"
                + " and ag.resource_id=" + keyAlias + ".id and (ag.grantee_type='WORKSPACE'"
                + " or (ag.grantee_type='USER' and ag.user_id=" + userSql + ")))";
    }

    /** Historical facts remain readable after a window closes if ordinary access still holds. */
    public static String notificationRead(String alias) {
        return "(" + alias + ".event <> '" + EVENT + "' or (jsonb_typeof(" + alias + ".payload)='object'"
                + " and " + alias + ".payload->>'axis' in ('TOKEN','CREDIT')"
                + " and exists(select 1 from llm_api_keys ak where "
                + "ak.public_id::text=" + alias + ".payload->>'keyId' and ("
                + keyRead("ak", alias + ".user_id") + "))))";
    }

    public boolean readableNotification(long notificationId, long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from notifications n "
                + "where n.id=? and n.user_id=? and " + notificationRead("n") + ")", Boolean.class,
                notificationId, userId));
    }

    public List<Long> eligibleReaders(long keyId, List<Long> candidates) {
        if (candidates.isEmpty()) { return List.of(); }
        String ids = "{" + candidates.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")) + "}";
        return jdbc.query("select u.id from users u join llm_api_keys k on k.id=? "
                + "where u.id=any(?::bigint[]) and " + keyRead("k", "u.id") + " order by u.id",
                (rs, n) -> rs.getLong(1), keyId, ids);
    }

    /** Candidates own either the workspace or the key; this alone never grants usage reading. */
    public List<Long> owners(long keyId) {
        return jdbc.query("""
                select distinct u.id from llm_api_keys k
                  join workspace_members m on m.workspace_id=k.workspace_id
                  join users u on u.id=m.user_id and u.status='ACTIVE'
                 where k.id=? and (m.role='OWNER' or exists(
                     select 1 from resource_access_grants g where g.resource_type='LLM_API_KEY'
                      and g.resource_id=k.id and g.role='OWNER'
                      and (g.grantee_type='WORKSPACE' or (g.grantee_type='USER' and g.user_id=u.id))))
                 order by u.id
                """, (rs, n) -> rs.getLong(1), keyId);
    }
}
