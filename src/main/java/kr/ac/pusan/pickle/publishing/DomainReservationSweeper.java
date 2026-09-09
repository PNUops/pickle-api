package kr.ac.pusan.pickle.publishing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hourly reclaim of released domain names. A released platform subdomain keeps
 * its row — and so its claim on the FQDN — for {@code
 * settings.platform_subdomain_reserve_days}; once the grace passes, this sweep
 * flips the row REMOVED (freeing the name for anyone) and revokes any
 * per-domain certificates. Custom rows carrying {@code releasedAt} are
 * leftovers of the old keep-forever behaviour (dated by the migration
 * backfill): under the current policy a custom name is never held after
 * release, so they are reclaimed with no grace at all.
 *
 * <p>Seven days before a platform reservation expires the owning workspace's
 * OWNER/EDITORs get an advance notice; the dedup key carries the release
 * timestamp, so a name that was revived and released again notifies afresh.
 * When the grace itself is seven days or shorter the notice would fire
 * immediately on release and say nothing useful — it is skipped.</p>
 *
 * <p>No audit entries: a system job acting on schedule is not an actor
 * (same convention as the other sweepers). No proxy-agent calls either —
 * a reclaimable row has no live route by definition; one that somehow still
 * serves is skipped, never yanked from under its traffic.</p>
 *
 * <p>Each candidate is decided in its OWN transaction under the domain row
 * lock, and every condition — {@code releasedAt}, expiry, the live-route
 * check — is re-read there. The scan list is only a snapshot: a user may
 * revive the name right at the expiry boundary ({@code releasedAt} cleared, a
 * fresh route attached), and a sweep flushing that snapshot afterwards would
 * silently erase a domain whose revive already returned success — with the
 * next apply then removing its route as a stray. Locked recheck first makes
 * the revive win whichever side commits first.</p>
 *
 * <p><b>The reclaim is where a released name becomes free, so it is also
 * where its A record must be gone.</b> The release itself takes the record
 * down with the vhost, but that step can fail and be left for the reconciler;
 * a row that still shows a record when its grace ends has the removal
 * retried here, outside the lock like every provider call, and is reclaimed
 * only once the provider confirms. A failure keeps the name reserved for
 * another hour rather than freeing a name that still points at the proxy.
 * A row at NONE has nothing to remove and reclaims as before.</p>
 */
@Component
public class DomainReservationSweeper {

    static final String JOB_ID = "domain-reservation-sweeper";
    static final int NOTICE_DAYS = 7;

    private static final Logger log = LoggerFactory.getLogger(DomainReservationSweeper.class);

    private final DomainRepository domainRepository;
    private final RouteRepository routeRepository;
    private final CertificateRepository certificateRepository;
    private final VmRepository vmRepository;
    private final SettingsService settingsService;
    private final NotificationService notificationService;
    private final TransactionTemplate transactionTemplate;
    private final PlatformDnsRecords dnsRecords;

    public DomainReservationSweeper(DomainRepository domainRepository,
            RouteRepository routeRepository, CertificateRepository certificateRepository,
            VmRepository vmRepository, SettingsService settingsService,
            NotificationService notificationService, TransactionTemplate transactionTemplate,
            PlatformDnsRecords dnsRecords) {
        this.domainRepository = domainRepository;
        this.routeRepository = routeRepository;
        this.certificateRepository = certificateRepository;
        this.vmRepository = vmRepository;
        this.settingsService = settingsService;
        this.notificationService = notificationService;
        this.transactionTemplate = transactionTemplate;
        this.dnsRecords = dnsRecords;
    }

    /** One sweep. Public and argument-free for JobRunr; tests call it directly. */
    @Recurring(id = JOB_ID, cron = "40 * * * *", zoneId = "Asia/Seoul")
    @Job(name = JOB_ID, retries = 0)
    public void sweep() {
        int graceDays = settingsService.integer(SettingsService.PLATFORM_SUBDOMAIN_RESERVE_DAYS,
                SubdomainPolicy.DEFAULT_RESERVE_DAYS);
        Instant now = Instant.now();
        int reclaimed = 0;
        int failed = 0;
        for (Domain candidate : domainRepository
                .findByReleasedAtIsNotNullAndStatusNot(DomainStatus.REMOVED)) {
            // One candidate's failure must not end the sweep. The loop is the
            // only thing that frees a reserved name, the job is registered with
            // no retries, and an exception escaping here stops every later
            // candidate for good: the name space quietly stops recycling and
            // nothing says so. Isolating each candidate turns that into one
            // loud row that gets retried on the next run.
            try {
                if (sweepOne(candidate.getId(), graceDays, now)) {
                    reclaimed++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("domain reservation sweep failed for domain {} ({})",
                        candidate.getId(), candidate.getFqdn(), e);
            }
        }
        if (reclaimed > 0 || failed > 0) {
            log.info("domain reservation sweep reclaimed {} released row(s), {} failed",
                    reclaimed, failed);
        }
    }

    /** What the locked evaluation of one candidate found. */
    private enum Verdict {
        /** Revived, reclaimed elsewhere, still serving, or simply not due. */
        LEAVE,
        /** Due, and no record to take down first. */
        RECLAIM,
        /** Due, but a record is (or may be) up and must come down first. */
        RECLAIM_AFTER_DNS
    }

    /** The locked verdict plus what the DNS step needs to know. */
    private record Decision(Verdict verdict, String fqdn) {
    }

    /**
     * One candidate: a locked evaluation, the DNS removal outside the lock
     * when one is owed, and a locked write that re-evaluates before acting.
     * Returns true when the row was reclaimed.
     */
    private boolean sweepOne(long domainId, int graceDays, Instant now) {
        Decision first = transactionTemplate.execute(tx -> evaluate(domainId, graceDays, now, true));
        if (first == null || first.verdict() == Verdict.LEAVE) {
            return false;
        }
        if (first.verdict() == Verdict.RECLAIM_AFTER_DNS
                && dnsRecords.remove(first.fqdn()) instanceof PlatformDnsRecords.Failed failure) {
            transactionTemplate.executeWithoutResult(tx -> domainRepository.findByIdForUpdate(domainId)
                    .ifPresent(domain -> domain.markDnsFailed(failure.error())));
            log.warn("domain reservation sweep kept {} reserved: dns record not removed: {}",
                    first.fqdn(), failure.error());
            return false;
        }
        return Boolean.TRUE.equals(transactionTemplate.execute(tx -> reclaim(domainId, graceDays,
                now, first.verdict() == Verdict.RECLAIM_AFTER_DNS)));
    }

    /**
     * Decides one candidate under the row lock, rechecking every condition
     * against the row's CURRENT committed state (the caller's scan is only a
     * snapshot). The advance notice is sent from here, once per release.
     */
    private Decision evaluate(long domainId, int graceDays, Instant now, boolean notice) {
        Domain domain = domainRepository.findByIdForUpdate(domainId).orElse(null);
        if (domain == null || domain.getStatus() == DomainStatus.REMOVED
                || domain.getReleasedAt() == null) {
            return new Decision(Verdict.LEAVE, null); // reclaimed elsewhere, or revived since the scan
        }
        if (routeRepository
                .findFirstByDomainIdAndStatusNot(domain.getId(), RouteStatus.REMOVED)
                .isPresent()) {
            return new Decision(Verdict.LEAVE, null); // still serving — a stale releasedAt must never take a route down
        }
        boolean custom = domain.getKind() == DomainKind.CUSTOM;
        Instant expiry = expiry(domain, graceDays);
        if (!now.isBefore(expiry)) {
            boolean recordUp = PlatformDnsRecords.managed(domain)
                    && domain.getDnsStatus() != DomainDnsStatus.NONE;
            return new Decision(recordUp ? Verdict.RECLAIM_AFTER_DNS : Verdict.RECLAIM,
                    domain.getFqdn());
        }
        if (notice && !custom && graceDays > NOTICE_DAYS
                && !now.isBefore(expiry.minus(NOTICE_DAYS, ChronoUnit.DAYS))) {
            notify(domain, NotificationEvent.DOMAIN_RESERVE_EXPIRING, expiry,
                    "domain_reserve_expiring:" + domain.getId()
                            + ":" + domain.getReleasedAt().toEpochMilli());
        }
        return new Decision(Verdict.LEAVE, null);
    }

    /**
     * The reclaim itself, under the lock again: the DNS step ran with no lock
     * held, so the row is re-evaluated first and a revive that landed in
     * between wins ("first commit wins", as before).
     */
    private boolean reclaim(long domainId, int graceDays, Instant now, boolean recordRemoved) {
        if (evaluate(domainId, graceDays, now, false).verdict() == Verdict.LEAVE) {
            return false;
        }
        Domain domain = domainRepository.findByIdForUpdate(domainId).orElseThrow();
        boolean custom = domain.getKind() == DomainKind.CUSTOM;
        Instant releasedAt = domain.getReleasedAt();
        Instant expiry = expiry(domain, graceDays);
        domain.setStatus(DomainStatus.REMOVED);
        // The stamp goes with the claim: a REMOVED row reserves nothing,
        // and a surviving releasedAt would keep it reading as "reserved".
        domain.setReleasedAt(null);
        if (recordRemoved) {
            domain.markDnsRemoved();
        }
        certificateRepository.findByDomainId(domain.getId()).stream()
                .filter(cert -> cert.getStatus() != CertificateStatus.REVOKED)
                .forEach(cert -> cert.setStatus(CertificateStatus.REVOKED));
        if (!custom) {
            notify(domain, NotificationEvent.DOMAIN_RESERVE_RELEASED, expiry,
                    "domain_reserve_released:" + domain.getId()
                            + ":" + releasedAt.toEpochMilli());
        }
        return true;
    }

    private static Instant expiry(Domain domain, int graceDays) {
        Instant releasedAt = domain.getReleasedAt();
        return domain.getKind() == DomainKind.CUSTOM ? releasedAt
                : releasedAt.plus(graceDays, ChronoUnit.DAYS);
    }

    private void notify(Domain domain, NotificationEvent event, Instant reservedUntil,
            String dedupKey) {
        // Every notice this sweeper sends is addressed through the domain's VM,
        // so a row that names no VM has no recipient rule here. Reached rather
        // than assumed: the repository lookup below throws on a null id, which
        // would take the whole sweep down with it. A kind that can exist
        // without a VM needs its own recipients before it can be notified, and
        // saying so at WARN is what keeps that from being a silent omission.
        if (domain.getVmId() == null) {
            log.warn("no notification recipients for domain {} ({}): kind {} names no VM",
                    domain.getId(), domain.getFqdn(), domain.getKind());
            return;
        }
        Vm vm = vmRepository.findById(domain.getVmId()).orElse(null);
        if (vm == null) {
            return;
        }
        notificationService.publish(notificationService.vmResponsibleIds(vm),
                event, Map.of("fqdn", domain.getFqdn(), "vmId", vm.getPublicId(),
                        "reservedUntil", reservedUntil), dedupKey);
    }
}
