package kr.ac.pusan.pickle.admin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionAuthorization;
import kr.ac.pusan.pickle.security.institution.InstitutionQuerySourceConstraints;
import kr.ac.pusan.pickle.security.institution.InstitutionQuerySourceConstraints.InstitutionLlmServiceReferenceFacts;
import kr.ac.pusan.pickle.security.institution.InstitutionQuerySourceConstraints.InstitutionLlmServiceRelation;
import kr.ac.pusan.pickle.security.institution.InstitutionQuerySourceConstraints.InstitutionLlmServiceRelationKind;
import kr.ac.pusan.pickle.security.institution.InstitutionQuerySourceConstraints.InstitutionQuerySourceConstraint;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fixed SQL bindings for actual key rows and server-resolved service references. */
@Component
public class InstitutionLlmQueryAccess {
    private final InstitutionAuthorization authorization;
    private final JdbcTemplate jdbc;

    public InstitutionLlmQueryAccess(InstitutionAuthorization authorization, JdbcTemplate jdbc) {
        this.authorization = authorization; this.jdbc = jdbc;
    }

    /** Applies only to a query whose actual key relation is bound as k. */
    public String observationKeyRows(AuthenticatedUser actor) {
        if (InstitutionLlmAccess.systemRead(actor)) { return "true"; }
        return keyRows(authorization.authority(actor).map(InstitutionQuerySourceConstraints::llmObservationSources)
                .map(InstitutionQuerySourceConstraints::sources).orElse(List.of()));
    }

    public boolean observationReader(AuthenticatedUser actor) {
        return InstitutionLlmAccess.systemRead(actor) || authorization.authority(actor)
                .map(InstitutionQuerySourceConstraints::llmObservationSources).map(value -> !value.sources().isEmpty()).orElse(false);
    }

    public kr.ac.pusan.pickle.orgs.OrgScope queryScope(AuthenticatedUser actor, @org.jspecify.annotations.Nullable UUID publicInstitution, @org.jspecify.annotations.Nullable Long actualInstitution) {
        if (InstitutionLlmAccess.systemRead(actor)) {
            return publicInstitution != null && actualInstitution == null ? kr.ac.pusan.pickle.orgs.OrgScope.nothing()
                    : kr.ac.pusan.pickle.orgs.OrgScope.of(actualInstitution);
        }
        Set<Long> institutions = new HashSet<>();
        var sources = authorization.authority(actor).map(InstitutionQuerySourceConstraints::llmObservationSources)
                .map(InstitutionQuerySourceConstraints::sources).orElse(List.of());
        for (var source : sources) {
            jdbc.query("select id from orgs where public_id=?", rs -> { institutions.add(rs.getLong(1)); }, source.requiredInstitutionId());
        }
        if (institutions.isEmpty()) {
            throw new kr.ac.pusan.pickle.common.error.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    kr.ac.pusan.pickle.common.error.ErrorCodes.ACCESS_DENIED, "접근 권한이 없습니다", "기관 LLM 관측 업무가 필요합니다.");
        }
        if (publicInstitution != null) {
            if (actualInstitution == null || !institutions.contains(actualInstitution)) {
                throw new kr.ac.pusan.pickle.common.error.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                        kr.ac.pusan.pickle.common.error.ErrorCodes.RESOURCE_NOT_FOUND, "리소스를 찾을 수 없습니다", "해당 기관을 찾을 수 없습니다.");
            }
            return kr.ac.pusan.pickle.orgs.OrgScope.of(actualInstitution);
        }
        return kr.ac.pusan.pickle.orgs.OrgScope.of(institutions);
    }

    /** Does not use the individual-key FULL global financial exception. */
    public String moneyKeyRows(AuthenticatedUser actor) {
        if (InstitutionLlmAccess.systemRead(actor)) { return "true"; }
        return keyRows(authorization.authority(actor).map(InstitutionQuerySourceConstraints::llmAggregateMoneySources)
                .map(InstitutionQuerySourceConstraints::sources).orElse(List.of()));
    }

    public boolean hasMoneySources(AuthenticatedUser actor) {
        return InstitutionLlmAccess.systemRead(actor) || authorization.authority(actor)
                .map(InstitutionQuerySourceConstraints::llmAggregateMoneySources).map(value -> !value.sources().isEmpty()).orElse(false);
    }

    /** Actual existing institutions plus live LLM request/key relations, never personal membership. */
    public List<InstitutionQuerySourceConstraint> serviceReferences(AuthenticatedUser actor) {
        if (authorization.authority(actor).isEmpty()) { return List.of(); }
        Set<UUID> institutions = new HashSet<>(jdbc.query("select public_id from orgs", (rs, n) -> rs.getObject(1, UUID.class)));
        Set<InstitutionLlmServiceRelation> relations = new HashSet<>(jdbc.query("""
                select 'LLM_REQUEST' as kind, r.public_id, o.public_id as institution, w.public_id as workspace
                  from requests r join orgs o on o.id=r.org_id join workspaces w on w.id=r.workspace_id
                 where r.resource_type='LLM_API_KEY' and w.deleted_at is null
                union all
                select 'LLM_KEY', k.public_id, o.public_id, w.public_id
                  from llm_api_keys k join orgs o on o.id=k.org_id join workspaces w on w.id=k.workspace_id
                 where w.deleted_at is null
                """, (rs, n) -> new InstitutionLlmServiceRelation(InstitutionLlmServiceRelationKind.valueOf(rs.getString(1)),
                        rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class))));
        return InstitutionQuerySourceConstraints.llmServiceReferenceSources(authorization.authority(actor).orElseThrow(),
                new InstitutionLlmServiceReferenceFacts(institutions, relations)).sources();
    }

    public String serviceReferenceKeyRows(AuthenticatedUser actor) {
        return InstitutionLlmAccess.systemRead(actor) ? "true" : keyRows(serviceReferences(actor));
    }

    private static String keyRows(List<InstitutionQuerySourceConstraint> sources) {
        List<String> branches = new ArrayList<>();
        for (var source : sources) {
            String institution = "k.org_id in (select id from orgs where public_id='" + source.requiredInstitutionId() + "'::uuid)";
            String workspaces = "k.workspace_id in (select id from workspaces";
            if (source.workspaceScope().mode() == InstitutionWorkspaceScope.Mode.LISTED) {
                if (source.workspaceScope().workspaceIds().isEmpty()) { continue; }
                workspaces += " where public_id in (" + uuidList(source.workspaceScope().workspaceIds()) + ")";
            }
            branches.add("(" + institution + " and " + workspaces + "))");
        }
        return branches.isEmpty() ? "false" : "(" + String.join(" or ", branches) + ")";
    }

    static String uuidList(Set<UUID> ids) {
        return ids.stream().sorted().map(id -> "'" + id + "'::uuid").collect(java.util.stream.Collectors.joining(","));
    }
}
