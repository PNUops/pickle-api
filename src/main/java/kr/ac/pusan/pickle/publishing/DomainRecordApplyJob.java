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
 * this push is in flight owns the name from that moment, and its own push —
 * queued behind the same lock — writes the final state.</p>
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
        nameLocks.underLock(fqdn, () -> {
            pushAll(domainId, fqdn, generation);
            return null;
        });
    }

    private void pushAll(long domainId, String fqdn, long generation) {
        List<DomainRecord> records = transactionTemplate.execute(tx ->
                recordRepository.findByDomainId(domainId).stream()
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
                    write(record.getId(), r -> r.markApplied(now));
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

    private boolean stillCurrent(long domainId, long generation) {
        Boolean current = transactionTemplate.execute(tx -> domainRepository.findById(domainId)
                .map(d -> d.getRecordsGeneration() == generation).orElse(false));
        return Boolean.TRUE.equals(current);
    }

    private void write(long recordId, java.util.function.Consumer<DomainRecord> change) {
        transactionTemplate.executeWithoutResult(tx ->
                recordRepository.findById(recordId).ifPresent(change));
    }
}
