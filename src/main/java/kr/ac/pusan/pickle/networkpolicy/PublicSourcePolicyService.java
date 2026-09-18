package kr.ac.pusan.pickle.networkpolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.config.NetworkPolicyProperties;
import kr.ac.pusan.pickle.networkpolicy.SourcePolicyStore.Stored;
import kr.ac.pusan.pickle.networkpolicy.dto.SourcePolicyPresetView;
import kr.ac.pusan.pickle.networkpolicy.dto.SourcePolicyView;
import kr.ac.pusan.pickle.publishing.Domain;
import kr.ac.pusan.pickle.publishing.DomainKind;
import kr.ac.pusan.pickle.publishing.DomainStatus;
import kr.ac.pusan.pickle.publishing.Route;
import kr.ac.pusan.pickle.publishing.RouteApplyJob;
import kr.ac.pusan.pickle.publishing.RouteGenerations;
import kr.ac.pusan.pickle.publishing.RouteRepository;
import kr.ac.pusan.pickle.publishing.RouteStatus;
import kr.ac.pusan.pickle.relay.PortForwardApplyState;
import kr.ac.pusan.pickle.relay.PortMapping;
import kr.ac.pusan.pickle.relay.PortMappingStatus;
import kr.ac.pusan.pickle.relay.Relay;
import kr.ac.pusan.pickle.relay.RelayGenerations;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Saves public source policies and ties each write to the existing generation model. */
@Service
public class PublicSourcePolicyService {

    private final NetworkPolicyProperties properties;
    private final SourcePolicyStore store;
    private final RouteRepository routes;
    private final RouteGenerations routeGenerations;
    private final RelayGenerations relayGenerations;
    private final JobScheduler jobs;
    private final RouteApplyJob routeApplyJob;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public PublicSourcePolicyService(NetworkPolicyProperties properties, SourcePolicyStore store,
            RouteRepository routes, RouteGenerations routeGenerations,
            RelayGenerations relayGenerations, JobScheduler jobs, RouteApplyJob routeApplyJob,
            AuditService audit, JdbcTemplate jdbc) {
        this.properties = properties;
        this.store = store;
        this.routes = routes;
        this.routeGenerations = routeGenerations;
        this.relayGenerations = relayGenerations;
        this.jobs = jobs;
        this.routeApplyJob = routeApplyJob;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    public void requireEnabled() {
        if (!properties.enabled()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.SOURCE_POLICY_DISABLED,
                    "출발지 정책 기능이 비활성화되어 있습니다",
                    "이 환경에서는 아직 공개 경로의 출발지 정책을 변경할 수 없습니다.");
        }
    }

    public SourcePolicyPresetView campusPreset(SourcePolicyTarget target) {
        requireEnabled();
        try {
            boolean ipv6 = target == SourcePolicyTarget.DOMAIN;
            return new SourcePolicyPresetView("CAMPUS", target,
                    properties.campusPreset(ipv6).cidrValues());
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.SOURCE_POLICY_UNAVAILABLE,
                    "교내 출발지 preset을 사용할 수 없습니다",
                    "이 환경에는 확인된 교내 출발지 IP 범위가 설정되어 있지 않습니다.");
        }
    }

    @Transactional(readOnly = true)
    public SourcePolicyView domainView(Domain domain) {
        requireEnabled();
        requireServedDomain(domain);
        Optional<Stored> stored = store.domain(domain.getId());
        Route route = routes.findFirstByDomainId(domain.getId()).orElse(null);
        return view(stored, route);
    }

    @Transactional
    public SourcePolicyView updateDomain(Domain domain, AuthenticatedUser actor,
            long expectedRevision, List<String> input, boolean adminIntervention, String ip) {
        requireEnabled();
        requireServedDomain(domain);
        List<String> cidrs = normalize(input, true);
        Route route = routes.findFirstByDomainId(domain.getId()).orElse(null);
        Stored saved = store.replaceDomain(domain.getId(), expectedRevision, cidrs, actor.id());
        if (route != null && route.getStatus() != RouteStatus.REMOVED) {
            route = routes.findByIdForApply(route.getId()).orElseThrow();
            route.setGeneration(routeGenerations.next());
            route.setSourcePolicyGeneration(route.getGeneration());
            route.setStatus(RouteStatus.PENDING);
            route.setLastError(null);
            if (domain.getStatus() == DomainStatus.ACTIVE) {
                enqueue(route.getId());
            }
        }
        audit.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.DOMAIN_SOURCE_POLICY_UPDATE, "domain", domain.getPublicId(),
                auditDetail(expectedRevision, saved, cidrs, adminIntervention), ip);
        return view(Optional.of(saved), route);
    }

    @Transactional(readOnly = true)
    public SourcePolicyView portMappingView(PortMapping mapping, Relay relay,
            boolean failed) {
        requireEnabled();
        Optional<Stored> stored = store.portMapping(mapping.getId());
        return view(stored, mapping, relay, failed);
    }

    @Transactional
    public SourcePolicyView updatePortMapping(PortMapping mapping, Relay relay,
            AuthenticatedUser actor, long expectedRevision, List<String> input,
            boolean failedBeforeWrite, boolean adminIntervention, String ip) {
        requireEnabled();
        List<String> cidrs = normalize(input, false);
        long generation = relayGenerations.bump(mapping.getRelayId());
        List<Long> stillPresent = jdbc.queryForList(
                "select id from port_mappings where id = ? for update", Long.class,
                mapping.getId());
        if (stillPresent.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "리소스를 찾을 수 없습니다", "해당 포트 포워딩이 존재하지 않습니다.");
        }
        Stored saved = store.replacePortMapping(mapping.getId(), expectedRevision, cidrs, actor.id());
        mapping.setLastChangeGeneration(generation);
        mapping.setSourcePolicyGeneration(generation);
        audit.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.PORT_MAPPING_SOURCE_POLICY_UPDATE, "port_mapping",
                mapping.getPublicId(),
                auditDetail(expectedRevision, saved, cidrs, adminIntervention), ip);
        return view(Optional.of(saved), mapping, relay, failedBeforeWrite);
    }

    private List<String> normalize(List<String> input, boolean ipv6Supported) {
        if (input == null) {
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("allowedCidrs", "출발지 CIDR 목록을 입력해 주세요.")));
        }
        try {
            List<CidrBlock> blocks = new ArrayList<>(input.size());
            for (String raw : input) {
                if (raw == null) {
                    throw new IllegalArgumentException("빈 출발지 주소는 사용할 수 없습니다.");
                }
                String value = raw.strip();
                blocks.add(value.contains("/") ? CidrBlock.parse(value) : CidrBlock.host(value));
            }
            SourcePolicy policy = new SourcePolicy(blocks);
            if (!ipv6Supported && blocks.stream().anyMatch(CidrBlock::ipv6)) {
                throw new IllegalArgumentException("포트 포워딩은 IPv4 출발지만 지원합니다.");
            }
            return policy.cidrValues();
        } catch (IllegalArgumentException invalid) {
            throw ApiException.validationFailed(List.of(new FieldValidationError(
                    "allowedCidrs", invalid.getMessage())));
        }
    }

    private static void requireServedDomain(Domain domain) {
        if (domain.getKind() == DomainKind.EXTERNAL || domain.getVmId() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "도메인을 찾을 수 없습니다", "해당 공개 도메인이 존재하지 않습니다.");
        }
    }

    private SourcePolicyView view(Optional<Stored> stored, Route route) {
        SourcePolicyApplyState state;
        if (route == null || route.getStatus() == RouteStatus.REMOVED) {
            state = SourcePolicyApplyState.INACTIVE;
        } else if (route.getStatus() == RouteStatus.FAILED) {
            state = SourcePolicyApplyState.FAILED;
        } else if (route.getStatus() == RouteStatus.APPLIED
                && route.getSourcePolicyGeneration() != null
                && route.getAppliedGeneration() != null
                && route.getAppliedGeneration() >= route.getGeneration()) {
            state = SourcePolicyApplyState.APPLIED;
        } else {
            state = SourcePolicyApplyState.PENDING;
        }
        return new SourcePolicyView(stored.map(Stored::revision).orElse(0L), stored.isPresent(),
                stored.map(Stored::allowedCidrs).orElse(List.of()), state,
                route == null ? null : route.getGeneration(),
                route == null ? null : route.getAppliedGeneration(),
                route == null ? null : route.getLastError(),
                stored.map(Stored::updatedAt).orElse(null));
    }

    private SourcePolicyView view(Optional<Stored> stored, PortMapping mapping, Relay relay,
            boolean failed) {
        if (mapping.getStatus() == PortMappingStatus.SUSPENDED) {
            return new SourcePolicyView(stored.map(Stored::revision).orElse(0L),
                    stored.isPresent(), stored.map(Stored::allowedCidrs).orElse(List.of()),
                    SourcePolicyApplyState.INACTIVE, mapping.getLastChangeGeneration(),
                    relay.getAppliedGeneration(), failed ? relay.getLastError() : null,
                    stored.map(Stored::updatedAt).orElse(null));
        }
        PortForwardApplyState relayState = failed ? PortForwardApplyState.FAILED
                : mapping.getSourcePolicyGeneration() != null
                        && relay.getAppliedGeneration() >= mapping.getLastChangeGeneration()
                        ? PortForwardApplyState.ACTIVE : PortForwardApplyState.PENDING;
        SourcePolicyApplyState state = switch (relayState) {
            case ACTIVE -> SourcePolicyApplyState.APPLIED;
            case FAILED -> SourcePolicyApplyState.FAILED;
            case PENDING -> SourcePolicyApplyState.PENDING;
        };
        return new SourcePolicyView(stored.map(Stored::revision).orElse(0L), stored.isPresent(),
                stored.map(Stored::allowedCidrs).orElse(List.of()), state,
                mapping.getLastChangeGeneration(), relay.getAppliedGeneration(),
                failed ? relay.getLastError() : null, stored.map(Stored::updatedAt).orElse(null));
    }

    private static Map<String, Object> auditDetail(long expectedRevision, Stored saved,
            List<String> cidrs, boolean adminIntervention) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("previousRevision", expectedRevision);
        detail.put("revision", saved.revision());
        detail.put("allowedCidrs", cidrs);
        detail.put("adminIntervention", adminIntervention);
        return detail;
    }

    private void enqueue(long routeId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                jobs.enqueue(() -> routeApplyJob.apply(routeId));
            }
        });
    }
}
