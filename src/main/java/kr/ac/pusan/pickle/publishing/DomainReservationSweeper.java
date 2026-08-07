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
import org.springframework.transaction.annotation.Transactional;

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
 * <p>Seven days before a platform reservation expires the owning group's
 * OWNER/EDITORs get an advance notice; the dedup key carries the release
 * timestamp, so a name that was revived and released again notifies afresh.
 * When the grace itself is seven days or shorter the notice would fire
 * immediately on release and say nothing useful — it is skipped.</p>
 *
 * <p>No audit entries: a system job acting on schedule is not an actor
 * (same convention as the other sweepers). No proxy-agent calls either —
 * a reclaimable row has no live route by definition; one that somehow still
 * serves is skipped, never yanked from under its traffic.</p>
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

    public DomainReservationSweeper(DomainRepository domainRepository,
            RouteRepository routeRepository, CertificateRepository certificateRepository,
            VmRepository vmRepository, SettingsService settingsService,
            NotificationService notificationService) {
        this.domainRepository = domainRepository;
        this.routeRepository = routeRepository;
        this.certificateRepository = certificateRepository;
        this.vmRepository = vmRepository;
        this.settingsService = settingsService;
        this.notificationService = notificationService;
    }

    /** One sweep. Public and argument-free for JobRunr; tests call it directly. */
    @Recurring(id = JOB_ID, cron = "40 * * * *", zoneId = "Asia/Seoul")
    @Job(name = JOB_ID, retries = 0)
    @Transactional
    public void sweep() {
        int graceDays = settingsService.integer(SettingsService.PLATFORM_SUBDOMAIN_RESERVE_DAYS,
                SubdomainPolicy.DEFAULT_RESERVE_DAYS);
        Instant now = Instant.now();
        int reclaimed = 0;
        for (Domain domain : domainRepository
                .findByReleasedAtIsNotNullAndStatusNot(DomainStatus.REMOVED)) {
            if (routeRepository
                    .findFirstByDomainIdAndStatusNot(domain.getId(), RouteStatus.REMOVED)
                    .isPresent()) {
                continue; // still serving — a stale releasedAt must never take a route down
            }
            boolean custom = domain.getKind() == DomainKind.CUSTOM;
            Instant expiry = custom ? domain.getReleasedAt()
                    : domain.getReleasedAt().plus(graceDays, ChronoUnit.DAYS);
            if (!now.isBefore(expiry)) {
                domain.setStatus(DomainStatus.REMOVED);
                certificateRepository.findByDomainId(domain.getId()).stream()
                        .filter(cert -> cert.getStatus() != CertificateStatus.REVOKED)
                        .forEach(cert -> cert.setStatus(CertificateStatus.REVOKED));
                if (!custom) {
                    notify(domain, NotificationEvent.DOMAIN_RESERVE_RELEASED, expiry,
                            "domain_reserve_released:" + domain.getId()
                                    + ":" + domain.getReleasedAt().toEpochMilli());
                }
                reclaimed++;
            } else if (!custom && graceDays > NOTICE_DAYS
                    && !now.isBefore(expiry.minus(NOTICE_DAYS, ChronoUnit.DAYS))) {
                notify(domain, NotificationEvent.DOMAIN_RESERVE_EXPIRING, expiry,
                        "domain_reserve_expiring:" + domain.getId()
                                + ":" + domain.getReleasedAt().toEpochMilli());
            }
        }
        if (reclaimed > 0) {
            log.info("domain reservation sweep reclaimed {} released row(s)", reclaimed);
        }
    }

    private void notify(Domain domain, NotificationEvent event, Instant reservedUntil,
            String dedupKey) {
        Vm vm = vmRepository.findById(domain.getVmId()).orElse(null);
        if (vm == null) {
            return;
        }
        notificationService.publish(notificationService.groupRoleHolderIds(vm.getGroupId(), true),
                event, Map.of("fqdn", domain.getFqdn(), "vmId", vm.getId(),
                        "reservedUntil", reservedUntil), dedupKey);
    }
}
