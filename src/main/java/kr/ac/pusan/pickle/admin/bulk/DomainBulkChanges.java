package kr.ac.pusan.pickle.admin.bulk;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeFieldDiff;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeKind;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeReason;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeRequest;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeSpec;
import kr.ac.pusan.pickle.admin.dto.AdminBulkDomainRenewalChange;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.publishing.AdminPublishingService;
import kr.ac.pusan.pickle.publishing.Certificate;
import kr.ac.pusan.pickle.publishing.CertificateRepository;
import kr.ac.pusan.pickle.publishing.CertificateStatus;
import kr.ac.pusan.pickle.publishing.Domain;
import kr.ac.pusan.pickle.publishing.DomainDnsStatus;
import kr.ac.pusan.pickle.publishing.DomainKind;
import kr.ac.pusan.pickle.publishing.DomainRecordRepository;
import kr.ac.pusan.pickle.publishing.DomainRecordStatus;
import kr.ac.pusan.pickle.publishing.DomainRepository;
import kr.ac.pusan.pickle.publishing.DomainStatus;
import kr.ac.pusan.pickle.publishing.Route;
import kr.ac.pusan.pickle.publishing.RouteRepository;
import kr.ac.pusan.pickle.publishing.RouteStatus;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The three domain kinds of a bulk change: renewal deadline, force release
 * and ownership re-verification, each the single admin path's change applied
 * to many names.
 *
 * <p>Reach mirrors the single paths: the system tier on every name, the
 * organisation tier on the names of an institution it operates, and
 * everything else NOT_FOUND, as the single paths answer 404. A name already
 * REMOVED is NOT_FOUND too, because the single paths resolve it as missing.
 * No kind refuses a role the class gate admits, so FORBIDDEN never appears.
 *
 * <p>How the single paths' answers map, per kind:
 * <ul>
 * <li>{@code DOMAIN_RENEWAL}: a name that is not external has no deadline
 * (the single path's 409 DOMAIN_NOT_ACTIVE for the kind) and is INELIGIBLE; an
 * external name already released (the same 409 for a released name) is
 * INVALID_STATE; a deadline that is not in the future is refused for the whole
 * request, as the single path's 422 is about the value and not the name; a
 * name already carrying exactly the new deadline is UNCHANGED.</li>
 * <li>{@code DOMAIN_FORCE_RELEASE}: the single path refuses no name it can
 * find, so neither does this. A name held in its release grace is released
 * for good, as the single path does; there is no UNCHANGED, because a name
 * already REMOVED is not found. The diff lists every irreversible effect the
 * administrator is confirming, each field present only when it applies:
 * {@code status} (to REMOVED, always), {@code releasedAt} (a held name's
 * release stamp, to null), {@code routeStatus} (the live route, to REMOVED),
 * {@code records} (how many record sets come down from a zone the platform
 * writes, to 0) and {@code activeCertificates} (how many of the name's own
 * certificates are not yet revoked, to 0).</li>
 * <li>{@code DOMAIN_VERIFY}: a name that is not a custom domain (the single
 * path's 409 DOMAIN_NOT_CUSTOM) is INELIGIBLE. Every custom name is APPLIED:
 * the single path accepts a request whatever the verification state, and a
 * check already in flight absorbs the new one silently, so there is no state
 * in which it would answer anything else. The diff it reports is the request
 * itself, {@code verification: null -> "REQUESTED"}, since nothing on the row
 * moves until the check runs.</li>
 * </ul>
 *
 * <p>Rows are locked in internal id order before the real judgment, so the
 * judgment sees what a concurrent single change committed. A change that
 * lands between the load and the lock moves the fingerprint and is answered
 * STALE; the name then shown is one the actor may already reach, since reach
 * is settled before any row is locked. Every domain row is locked before any
 * route, record or certificate row is touched. The one single path that
 * locks explicitly, the record removal, takes the domain row before its
 * records, the same order. The others take no explicit lock and write their
 * domain and route rows at flush, so a single change racing a bulk change on
 * the same name waits on the domain row; should the database still find a
 * cycle it aborts one transaction, which is whole-call failure and not a
 * partial write.
 *
 * <p>Everything that leaves the process (route pushes, record removals,
 * verification checks, mail) is queued for after the commit by the single
 * paths' own code, and under a bulk change each of those callbacks is
 * isolated, so one that fails does not skip the others.
 */
@Component
class DomainBulkChanges extends BulkChangeHandler<Domain> {

    private final DomainRepository domainRepository;
    private final RouteRepository routeRepository;
    private final CertificateRepository certificateRepository;
    private final DomainRecordRepository recordRepository;
    private final AdminPublishingService publishingService;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    DomainBulkChanges(DomainRepository domainRepository, RouteRepository routeRepository,
            CertificateRepository certificateRepository, DomainRecordRepository recordRepository,
            AdminPublishingService publishingService,
            EntityManager entityManager, ObjectMapper objectMapper) {
        this.domainRepository = domainRepository;
        this.routeRepository = routeRepository;
        this.certificateRepository = certificateRepository;
        this.recordRepository = recordRepository;
        this.publishingService = publishingService;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    @Override
    Set<AdminBulkChangeKind> kinds() {
        return Set.of(AdminBulkChangeKind.DOMAIN_RENEWAL, AdminBulkChangeKind.DOMAIN_FORCE_RELEASE,
                AdminBulkChangeKind.DOMAIN_VERIFY);
    }

    @Override
    void validate(AdminBulkChangeRequest request, List<FieldValidationError> errors,
            Instant now) {
        AdminBulkChangeSpec change = request.change();
        switch (change.kind()) {
            case DOMAIN_RENEWAL -> {
                FieldValidationError tooEarly = AdminPublishingService.renewalInPast(
                        change.domainRenewal().renewDueAt(), now);
                if (tooEarly != null) {
                    errors.add(new FieldValidationError("change.domainRenewal.renewDueAt",
                            tooEarly.message()));
                }
            }
            case DOMAIN_FORCE_RELEASE, DOMAIN_VERIFY -> {
                // No body on the single path, so nothing to check here.
            }
            default -> throw new IllegalStateException("not a domain kind: " + change.kind());
        }
    }

    @Override
    Map<UUID, Domain> load(AdminBulkChangeRequest request) {
        Map<UUID, Domain> domains = new LinkedHashMap<>();
        for (UUID id : request.targetIds()) {
            domainRepository.findByPublicId(id)
                    .filter(domain -> domain.getStatus() != DomainStatus.REMOVED)
                    .ifPresent(domain -> domains.put(id, domain));
        }
        return domains;
    }

    @Override
    long orderKey(Domain domain) {
        return domain.getId();
    }

    @Override
    String name(Domain domain) {
        return domain.getFqdn();
    }

    @Override
    @Nullable AdminBulkChangeReason accessRefusal(AuthenticatedUser actor, Domain domain,
            AdminBulkChangeSpec change) {
        return AdminPublishingService.mayIntervene(actor, domain) ? null
                : AdminBulkChangeReason.NOT_FOUND;
    }

    @Override
    Map<String, Object> fingerprintValues(Domain domain, AdminBulkChangeSpec change) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", domain.getKind().name());
        values.put("status", domain.getStatus().name());
        switch (change.kind()) {
            case DOMAIN_RENEWAL -> {
                values.put("releasedAt", BulkFingerprints.plain(domain.getReleasedAt()));
                values.put("renewDueAt", BulkFingerprints.plain(domain.getRenewDueAt()));
            }
            case DOMAIN_FORCE_RELEASE -> {
                values.put("releasedAt", BulkFingerprints.plain(domain.getReleasedAt()));
                values.put("vmId", domain.getVmId());
                values.put("recordsGeneration", domain.getRecordsGeneration());
                values.put("dnsStatus", BulkFingerprints.plain(domain.getDnsStatus()));
                values.put("records", recordRepository.findByDomainIdAndStatusNotOrderByIdAsc(
                                domain.getId(), DomainRecordStatus.REMOVED).stream()
                        .map(record -> record.getId() + ":" + record.getStatus().name())
                        .toList());
                Route live = liveRoute(domain);
                values.put("liveRoute", live == null ? null
                        : live.getId() + ":" + live.getStatus().name());
                values.put("certificates", certificateRepository.findByDomainId(domain.getId())
                        .stream().sorted(Comparator.comparing(Certificate::getId))
                        .map(cert -> cert.getId() + ":" + cert.getStatus().name())
                        .toList());
            }
            case DOMAIN_VERIFY -> {
                // Kind and status are what the single path reads.
            }
            default -> throw new IllegalStateException("not a domain kind: " + change.kind());
        }
        return values;
    }

    @Override
    Judgement judge(AuthenticatedUser actor, Domain domain, AdminBulkChangeSpec change,
            Instant now) {
        return switch (change.kind()) {
            case DOMAIN_RENEWAL -> judgeRenewal(domain, change.domainRenewal(), now);
            case DOMAIN_FORCE_RELEASE -> judgeRelease(domain);
            case DOMAIN_VERIFY -> judgeVerify(domain);
            default -> throw new IllegalStateException("not a domain kind: " + change.kind());
        };
    }

    private Judgement judgeRenewal(Domain domain, AdminBulkDomainRenewalChange renewal,
            Instant now) {
        if (!AdminPublishingService.hasRenewalDeadline(domain)) {
            return Judgement.refused(AdminBulkChangeReason.INELIGIBLE);
        }
        if (domain.getReleasedAt() != null) {
            return Judgement.refused(AdminBulkChangeReason.INVALID_STATE);
        }
        if (Objects.equals(domain.getRenewDueAt(), renewal.renewDueAt())) {
            return Judgement.unchanged();
        }
        // The plan is the instant this judgment was made at, so the write
        // tests the deadline against the same moment.
        return Judgement.change(List.of(diff(objectMapper, "renewDueAt", domain.getRenewDueAt(),
                renewal.renewDueAt())), now);
    }

    private Judgement judgeRelease(Domain domain) {
        List<AdminBulkChangeFieldDiff> fields = new ArrayList<>();
        fields.add(diff(objectMapper, "status", domain.getStatus(), DomainStatus.REMOVED));
        if (domain.getReleasedAt() != null) {
            fields.add(diff(objectMapper, "releasedAt", domain.getReleasedAt(), null));
        }
        Route live = liveRoute(domain);
        if (live != null) {
            fields.add(diff(objectMapper, "routeStatus", live.getStatus(), RouteStatus.REMOVED));
        }
        long records = zoneRecords(domain, live);
        if (records > 0) {
            fields.add(diff(objectMapper, "records", records, 0));
        }
        long certificates = certificateRepository.findByDomainId(domain.getId()).stream()
                .filter(cert -> cert.getStatus() != CertificateStatus.REVOKED)
                .count();
        if (certificates > 0) {
            fields.add(diff(objectMapper, "activeCertificates", certificates, 0));
        }
        return Judgement.change(fields, null);
    }

    private Judgement judgeVerify(Domain domain) {
        if (!AdminPublishingService.isVerifiable(domain)) {
            return Judgement.refused(AdminBulkChangeReason.INELIGIBLE);
        }
        return Judgement.change(List.of(diff(objectMapper, "verification", null, "REQUESTED")),
                null);
    }

    /**
     * The record sets the release takes down from a zone the platform writes:
     * an external name's own sets, or the one record a served platform name
     * has once the platform has written it. Mirrors the teardown's own tests.
     */
    private long zoneRecords(Domain domain, @Nullable Route live) {
        if (domain.getKind() == DomainKind.EXTERNAL) {
            return recordRepository.findByDomainIdAndStatusNotOrderByIdAsc(domain.getId(),
                    DomainRecordStatus.REMOVED).size();
        }
        boolean owesRemoval = live != null && domain.getKind().servedByPlatformProxy()
                && domain.getDnsStatus() != DomainDnsStatus.NONE;
        return owesRemoval ? 1 : 0;
    }

    private @Nullable Route liveRoute(Domain domain) {
        return routeRepository.findFirstByDomainIdAndStatusNot(domain.getId(), RouteStatus.REMOVED)
                .orElse(null);
    }

    @Override
    void lock(Domain domain) {
        // Lock and re-read in one statement, so the judgment that follows sees
        // the row a concurrent single change left, not the one loaded before.
        entityManager.refresh(domain, LockModeType.PESSIMISTIC_WRITE);
    }

    @Override
    @Nullable AdminBulkChangeReason write(AuthenticatedUser actor, Domain domain,
            Judgement judgement, AdminBulkChangeSpec change, UUID batchId, String ip) {
        return switch (change.kind()) {
            case DOMAIN_RENEWAL -> {
                AdminBulkDomainRenewalChange renewal = change.domainRenewal();
                yield reasonOf(publishingService.changeRenewal(actor, domain,
                        renewal.renewDueAt(), renewal.reason(), (Instant) judgement.plan(),
                        batchId, ip));
            }
            case DOMAIN_FORCE_RELEASE -> {
                publishingService.releaseByAdmin(actor, domain, batchId, ip);
                yield null;
            }
            case DOMAIN_VERIFY -> reasonOf(publishingService.requestVerification(actor, domain,
                    batchId, ip));
            default -> throw new IllegalStateException("not a domain kind: " + change.kind());
        };
    }

    /**
     * The single path's refusal, as the reason code the bulk answer carries.
     * The judgment has already told the kind refusals apart from the state
     * ones, so what reaches here is a state that moved underneath it.
     */
    private static @Nullable AdminBulkChangeReason reasonOf(@Nullable ApiException refusal) {
        if (refusal == null) {
            return null;
        }
        return switch (refusal.getCode()) {
            case ErrorCodes.VALIDATION_FAILED -> AdminBulkChangeReason.VALIDATION;
            case ErrorCodes.DOMAIN_NOT_CUSTOM -> AdminBulkChangeReason.INELIGIBLE;
            default -> AdminBulkChangeReason.INVALID_STATE;
        };
    }
}
