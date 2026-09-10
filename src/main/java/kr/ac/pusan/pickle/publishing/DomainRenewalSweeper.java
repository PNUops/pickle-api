package kr.ac.pusan.pickle.publishing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asks whether anyone still wants an external name, and takes it back when
 * nobody answers.
 *
 * <p>Every other resource here has something above it that expires: a VM has
 * its usage period, a platform subdomain has the VM it is published from. A
 * name that only holds records has nothing, and it is issued without approval,
 * so left alone it would be held forever by whoever typed it first. The
 * deadline on the row is what replaces that, and renewing it is a button
 * rather than a check the platform makes for the owner: a site that is briefly
 * down and a name whose owner has gone are indistinguishable from the outside,
 * and only the owner can tell them apart.</p>
 *
 * <p>Lapsing is a release, not a deletion. The records come down — which is
 * how an owner who stopped reading mail finds out, since the site stops
 * answering — and the name goes into the same reservation grace a release
 * gets, so it can still be taken back for a while. Only the reservation sweep
 * frees it after that.</p>
 *
 * <p>An owner who has left the university hears none of this: the notices go
 * to an address that no longer accepts them and the name is reclaimed quietly
 * some months later. That is the case this exists for rather than a gap in
 * it, but it is also why the screen has to say that a missed renewal loses
 * the name.</p>
 */
@Component
public class DomainRenewalSweeper {

    static final String JOB_ID = "domain-renewal-sweeper";

    private static final Logger log = LoggerFactory.getLogger(DomainRenewalSweeper.class);

    private final DomainRepository domainRepository;
    private final DomainRecordsService recordsService;
    private final DomainRenewalPolicy renewalPolicy;
    private final SettingsService settingsService;
    private final NotificationService notificationService;
    private final TransactionTemplate transactionTemplate;

    public DomainRenewalSweeper(DomainRepository domainRepository,
            DomainRecordsService recordsService, DomainRenewalPolicy renewalPolicy,
            SettingsService settingsService, NotificationService notificationService,
            TransactionTemplate transactionTemplate) {
        this.domainRepository = domainRepository;
        this.recordsService = recordsService;
        this.renewalPolicy = renewalPolicy;
        this.settingsService = settingsService;
        this.notificationService = notificationService;
        this.transactionTemplate = transactionTemplate;
    }

    /** One sweep. Public and argument-free for JobRunr; tests call it directly. */
    @Recurring(id = JOB_ID, cron = "10 4 * * *", zoneId = "Asia/Seoul")
    @Job(name = JOB_ID, retries = 0)
    public void sweep() {
        List<Integer> stages = renewalPolicy.noticeStages();
        int furthest = stages.isEmpty() ? 0 : stages.getLast();
        Instant now = Instant.now();
        int notified = 0;
        int lapsed = 0;
        int failed = 0;
        for (Domain candidate : domainRepository
                .findByKindAndStatusNotAndReleasedAtIsNullAndRenewDueAtLessThanEqual(
                        DomainKind.EXTERNAL, DomainStatus.REMOVED,
                        now.plus(furthest, ChronoUnit.DAYS))) {
            // Isolated per candidate, the same reason the reservation sweep
            // isolates its own: this loop is the only thing that reclaims an
            // unrenewed name, and one exception escaping it would stop every
            // later row for good with nothing saying so.
            try {
                if (candidate.getRenewDueAt().isAfter(now)) {
                    if (notice(candidate.getId(), stages, now)) {
                        notified++;
                    }
                } else if (lapse(candidate.getId(), now)) {
                    lapsed++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("domain renewal sweep failed for domain {} ({})", candidate.getId(),
                        candidate.getFqdn(), e);
            }
        }
        if (notified > 0 || lapsed > 0 || failed > 0) {
            log.info("domain renewal sweep notified {}, lapsed {}, failed {}",
                    notified, lapsed, failed);
        }
    }

    /**
     * The advance notice for one row, at the nearest stage that covers how
     * long is left. A domain created inside a stage skips the ones it is
     * already past rather than sending them all at once; the dedup key carries
     * the deadline, so renewing re-arms every stage for the new one.
     */
    private boolean notice(long domainId, List<Integer> stages, Instant now) {
        Domain domain = transactionTemplate.execute(tx ->
                domainRepository.findById(domainId).orElse(null));
        if (domain == null || domain.getReleasedAt() != null || domain.getRenewDueAt() == null) {
            return false;
        }
        long daysLeft = ChronoUnit.DAYS.between(now, domain.getRenewDueAt());
        Integer stage = stages.stream().filter(s -> daysLeft <= s).findFirst().orElse(null);
        if (stage == null) {
            return false;
        }
        notificationService.publish(DomainRecipients.of(notificationService, domain),
                NotificationEvent.DOMAIN_RENEWAL_DUE,
                Map.of("fqdn", domain.getFqdn(), "domainId", domain.getPublicId(),
                        "renewDueAt", domain.getRenewDueAt()),
                "domain_renewal_due:" + domain.getId() + ":"
                        + domain.getRenewDueAt().toEpochMilli() + ":D" + stage);
        return true;
    }

    /**
     * Takes the records down and puts the name into its reservation grace.
     *
     * <p>Re-read under the row lock first: the scan is a snapshot, and an
     * owner who renewed at the boundary would otherwise have a deadline they
     * already moved acted on. Whichever commits first wins, and the renewal
     * winning is the safe direction.</p>
     */
    private boolean lapse(long domainId, Instant now) {
        Boolean released = transactionTemplate.execute(tx -> {
            Domain domain = domainRepository.findByIdForUpdate(domainId).orElse(null);
            if (domain == null || domain.getStatus() == DomainStatus.REMOVED
                    || domain.getReleasedAt() != null || domain.getRenewDueAt() == null
                    || domain.getRenewDueAt().isAfter(now)) {
                return false;
            }
            recordsService.removeAll(domainId);
            domain.setReleasedAt(now);
            return true;
        });
        if (!Boolean.TRUE.equals(released)) {
            return false;
        }
        Domain domain = transactionTemplate.execute(tx ->
                domainRepository.findById(domainId).orElseThrow());
        int graceDays = settingsService.integer(SettingsService.PLATFORM_SUBDOMAIN_RESERVE_DAYS,
                SubdomainPolicy.DEFAULT_RESERVE_DAYS);
        notificationService.publish(DomainRecipients.of(notificationService, domain),
                NotificationEvent.DOMAIN_RENEWAL_LAPSED,
                Map.of("fqdn", domain.getFqdn(), "domainId", domain.getPublicId(),
                        "reservedUntil", now.plus(graceDays, ChronoUnit.DAYS)),
                "domain_renewal_lapsed:" + domain.getId() + ":" + now.toEpochMilli());
        log.info("domain renewal lapsed for {}: records removed, name reserved for {} day(s)",
                domain.getFqdn(), graceDays);
        return true;
    }
}
