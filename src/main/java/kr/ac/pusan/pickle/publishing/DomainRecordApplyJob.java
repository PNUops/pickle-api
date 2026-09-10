package kr.ac.pusan.pickle.publishing;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.publishing.dns.DnsProviderException;
import kr.ac.pusan.pickle.publishing.dns.DnsRecordProvider;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pushes one domain's record sets into the zone.
 *
 * <p>The unit is the domain, not the set. An edit changes a domain's records
 * as a whole, so one push carries every set it has and one counter orders
 * those pushes; a counter per set would let two edits to one domain race
 * instead of the later one superseding the earlier. The per-row status is the
 * outcome of this push for each set, which can differ between sets inside it.</p>
 *
 * <p>Provider calls run outside any database transaction and under the name's
 * lock, the same discipline the vhost push follows. The generation is re-read
 * before each call rather than once at the start: a newer intent written while
 * this push is in flight owns the name from that moment, and this push stands
 * down so that the newer one, queued behind the same lock, writes the final
 * state.</p>
 *
 * <p>That guard runs before the call, so it cannot see an edit that commits
 * during one. What closes the gap on the other side is the status write, which
 * lands only while the row still holds what was pushed: a row stamped APPLIED
 * for values the zone never received would be skipped by the superseding push
 * as already applied, and the disagreement would have nothing left to find
 * it.</p>
 */
@Component
public class DomainRecordApplyJob {

    private static final Logger log = LoggerFactory.getLogger(DomainRecordApplyJob.class);

    private final DnsRecordProvider provider;
    private final DomainRepository domainRepository;
    private final DomainRecordRepository recordRepository;
    private final TransactionTemplate transactionTemplate;
    private final DnsNameLocks nameLocks;

    public DomainRecordApplyJob(DnsRecordProvider provider, DomainRepository domainRepository,
            DomainRecordRepository recordRepository, TransactionTemplate transactionTemplate,
            DnsNameLocks nameLocks) {
        this.provider = provider;
        this.domainRepository = domainRepository;
        this.recordRepository = recordRepository;
        this.transactionTemplate = transactionTemplate;
        this.nameLocks = nameLocks;
    }

    @Job(name = "domain-records-apply %0", retries = 0)
    public void apply(long domainId) {
        Domain domain = transactionTemplate.execute(tx -> domainRepository.findById(domainId)
                .orElse(null));
        if (domain == null) {
            return;
        }
        if (!provider.configured()) {
            // Nothing is recorded as applied against a provider that writes
            // nothing. The rows stay owed and the reconciler picks them up once
            // one is configured.
            log.warn("domain-records-apply skipped for {}: dns provider unconfigured",
                    domain.getFqdn());
            return;
        }
        String fqdn = domain.getFqdn();
        long generation = domain.getRecordsGeneration();
        // Released and retired both mean the same thing here: the name is no
        // longer this domain's to write. A release keeps the row alive through
        // its reservation grace, so asking only about REMOVED would leave the
        // whole grace period as a window in which a set could still be pushed
        // under a name that is being taken back.
        boolean letGo = domain.getStatus() == DomainStatus.REMOVED
                || domain.getReleasedAt() != null;
        nameLocks.underLock(fqdn, () -> {
            if (letGo && claimedByAnother(domainId, fqdn)) {
                // The name went back into the pool and somebody took it. These
                // rows name sets in the zone that are now that owner's, so the
                // only safe thing to do with them is forget them: removing
                // them would delete records this domain no longer speaks for.
                int dropped = dropOwed(domainId);
                log.warn("domain-records-apply for {} dropped {} owed row(s): the name belongs "
                        + "to another domain now", fqdn, dropped);
                return null;
            }
            pushAll(domainId, fqdn, generation, letGo);
            return null;
        });
    }

    private void pushAll(long domainId, String fqdn, long generation, boolean letGo) {
        List<DomainRecord> records = transactionTemplate.execute(tx ->
                recordRepository.findByDomainIdOrderByIdAsc(domainId).stream()
                        .filter(r -> r.getStatus() != DomainRecordStatus.APPLIED)
                        .toList());
        if (records == null || records.isEmpty()) {
            return;
        }
        int applied = 0;
        int removed = 0;
        int failed = 0;
        for (DomainRecord record : records) {
            if (!stillCurrent(domainId, generation)) {
                log.info("domain-records-apply for {} stood down: a newer edit owns the name",
                        fqdn);
                return;
            }
            String owner = absolute(record.getName(), fqdn);
            if (letGo && record.getStatus() != DomainRecordStatus.REMOVED) {
                // A domain that has been let go writes nothing. Its name is
                // out of its hands, and putting a set back under it would
                // publish a record for a name this platform no longer says is
                // theirs — and, being owed a write, would keep the reclaim
                // finding work for a name it can then never free.
                write(record.getId(), DomainRecord::markRemovalOwed);
                log.info("domain-records-apply {} {} turned into a removal: the name was released",
                        record.getType(), owner);
                continue;
            }
            try {
                if (record.getStatus() == DomainRecordStatus.REMOVED) {
                    provider.remove(owner, record.getType());
                    removed++;
                    // The row goes only after the zone confirms: dropped
                    // earlier, a failed removal would leave a set in the zone
                    // that nothing remembers owing.
                    write(record.getId(), r -> recordRepository.delete(r));
                } else {
                    provider.ensure(owner, record.getType(), record.getRrdatas(), record.getTtl());
                    applied++;
                    Instant now = Instant.now();
                    // Marked applied only if the row still asks for what was
                    // just written. The generation check above runs BEFORE the
                    // provider call, so an edit that commits during it is
                    // invisible here; this row would otherwise be stamped
                    // APPLIED while holding values the zone has never seen, and
                    // the superseding push skips APPLIED rows — the zone and
                    // the row would disagree with nothing left to notice.
                    if (!writeIfUnchanged(record, r -> r.markApplied(now))) {
                        applied--;
                        log.info("domain-records-apply {} {} not recorded: the set moved "
                                + "under the push", record.getType(), owner);
                    }
                }
            } catch (DnsProviderException e) {
                failed++;
                write(record.getId(), r -> r.markFailed(e.getMessage()));
                log.warn("domain-records-apply {} {} failed: {}", record.getType(), owner,
                        e.getMessage());
            }
        }
        log.info("domain-records-apply for {}: {} applied, {} removed, {} failed",
                fqdn, applied, removed, failed);
    }

    /** The absolute owner name: an empty relative name is the domain itself. */
    static String absolute(String name, String fqdn) {
        return name.isEmpty() ? fqdn : name + "." + fqdn;
    }

    /** Whether a live domain row now holds this name, read fresh under the lock. */
    private boolean claimedByAnother(long domainId, String fqdn) {
        Boolean claimed = transactionTemplate.execute(tx -> domainRepository
                .findFirstByFqdnAndStatusNot(fqdn, DomainStatus.REMOVED)
                .filter(other -> other.getId() != domainId)
                .isPresent());
        return Boolean.TRUE.equals(claimed);
    }

    /** Forgets a retired domain's owed rows without touching the zone. */
    private int dropOwed(long domainId) {
        Integer dropped = transactionTemplate.execute(tx -> {
            List<DomainRecord> owed = recordRepository.findByDomainIdOrderByIdAsc(domainId).stream()
                    .filter(r -> r.getStatus() != DomainRecordStatus.APPLIED)
                    .toList();
            owed.forEach(recordRepository::delete);
            return owed.size();
        });
        return dropped == null ? 0 : dropped;
    }

    private boolean stillCurrent(long domainId, long generation) {
        Boolean current = transactionTemplate.execute(tx -> domainRepository.findById(domainId)
                .map(d -> d.getRecordsGeneration() == generation).orElse(false));
        return Boolean.TRUE.equals(current);
    }

    private void write(long recordId, java.util.function.Consumer<DomainRecord> change) {
        transactionTemplate.executeWithoutResult(tx ->
                recordRepository.findById(recordId).ifPresent(change));
    }

    /**
     * Applies {@code change} only while the row still holds the values that
     * were pushed. Compared on the values rather than on the generation: the
     * generation moves for any edit to the domain, and a set that was not
     * touched by that edit was still written correctly.
     *
     * @return whether the row was written
     */
    private boolean writeIfUnchanged(DomainRecord pushed,
            java.util.function.Consumer<DomainRecord> change) {
        Boolean written = transactionTemplate.execute(tx -> recordRepository
                .findById(pushed.getId())
                .filter(r -> r.getRrdatas().equals(pushed.getRrdatas()))
                .filter(r -> r.getTtl() == pushed.getTtl())
                .filter(r -> r.getStatus() == pushed.getStatus())
                .map(r -> {
                    change.accept(r);
                    return true;
                })
                .orElse(false));
        return Boolean.TRUE.equals(written);
    }
}
