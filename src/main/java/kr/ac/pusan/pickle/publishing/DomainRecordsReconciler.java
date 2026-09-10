package kr.ac.pusan.pickle.publishing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import kr.ac.pusan.pickle.config.DnsProperties;
import kr.ac.pusan.pickle.publishing.dns.DnsProviderException;
import kr.ac.pusan.pickle.publishing.dns.DnsRecord;
import kr.ac.pusan.pickle.publishing.dns.DnsRecordProvider;
import kr.ac.pusan.pickle.publishing.dns.DnsRecordType;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brings the zone back in line with what external domains say they hold.
 *
 * <p>External names are outside every reconciler that existed before this:
 * the route resync walks routes and an external domain has none, and the zone
 * reconcile next to it decides by the platform's own address, which no
 * external record carries. Without this the only thing that ever pushes their
 * records is the apply job, and an apply that died mid-push leaves rows owed a
 * write that nothing comes back for.</p>
 *
 * <p>The push half is driven by the rows rather than by a comparison against
 * the listing. The provider's ensure already reads the set and writes only
 * when it differs, so re-deciding that here would be a second copy of the
 * comparison — including TXT's presentation form, which is exactly the
 * comparison that has been got wrong once already.</p>
 *
 * <p>The listing is used for the other direction, which the rows cannot see:
 * a set the zone holds that no row claims. It has one real source. A push that
 * reached the zone and then failed to record itself leaves a row that looks
 * as though it was never applied, and the next edit drops such a row outright
 * rather than queueing a removal for it. The zone keeps the set, and nothing
 * remembers owing it.</p>
 */
@Component
public class DomainRecordsReconciler {

    private static final Logger log = LoggerFactory.getLogger(DomainRecordsReconciler.class);

    /** The types an owner can put there, and therefore the only ones in scope. */
    private static final Set<String> MANAGED_TYPES =
            Set.of(DnsRecordType.A.name(), DnsRecordType.AAAA.name(),
                    DnsRecordType.CNAME.name(), DnsRecordType.TXT.name());

    private final DnsRecordProvider provider;
    private final DnsProperties dnsProperties;
    private final SettingsService settingsService;
    private final DomainRepository domainRepository;
    private final DomainRecordRepository recordRepository;
    private final DomainRecordApplyJob applyJob;
    private final DnsNameLocks nameLocks;
    private final TransactionTemplate transactionTemplate;

    public DomainRecordsReconciler(DnsRecordProvider provider, DnsProperties dnsProperties,
            SettingsService settingsService, DomainRepository domainRepository,
            DomainRecordRepository recordRepository, DomainRecordApplyJob applyJob,
            DnsNameLocks nameLocks, TransactionTemplate transactionTemplate) {
        this.provider = provider;
        this.dnsProperties = dnsProperties;
        this.settingsService = settingsService;
        this.domainRepository = domainRepository;
        this.recordRepository = recordRepository;
        this.applyJob = applyJob;
        this.nameLocks = nameLocks;
        this.transactionTemplate = transactionTemplate;
    }

    /** What one root's pass did, for the log and for a test to read. */
    public record Reconciliation(String rootDomain, List<String> pushed, List<String> pruned,
            List<String> orphansLeft) {
    }

    /**
     * Pushes every external domain that is owed a write and reports the sets
     * the zone holds under an external name that no row claims.
     *
     * <p>Never throws, the same posture the zone reconcile beside it takes: a
     * root whose listing fails still gets its pushes, because those need no
     * listing, and only its orphan half is skipped.</p>
     */
    public List<Reconciliation> reconcile() {
        if (!provider.configured()) {
            log.warn("domain-records reconcile skipped: provider unconfigured");
            return List.of();
        }
        List<Reconciliation> results = new ArrayList<>();
        for (String root : settingsService.stringList(SettingsService.ALLOWED_ROOT_DOMAINS)) {
            String rootDomain = root.toLowerCase(Locale.ROOT);
            List<Domain> external = domainRepository
                    .findByRootDomainAndStatusNot(rootDomain, DomainStatus.REMOVED).stream()
                    .filter(domain -> domain.getKind() == DomainKind.EXTERNAL)
                    .toList();
            results.add(reconcileRoot(rootDomain, external));
        }
        return results;
    }

    private Reconciliation reconcileRoot(String rootDomain, List<Domain> external) {
        List<String> pushed = new ArrayList<>();
        for (Domain domain : external) {
            if (owesTheZone(domain.getId())) {
                // The apply job is the one writer for a domain's records, so
                // the reconciler asks it rather than pushing itself: one place
                // holds the generation guard and the per-row bookkeeping.
                applyJob.apply(domain.getId());
                pushed.add(domain.getFqdn());
            }
        }
        List<DnsRecord> zone;
        try {
            zone = provider.listRecords(rootDomain);
        } catch (DnsProviderException e) {
            log.error("domain-records reconcile listed nothing for {}: {}", rootDomain,
                    e.getMessage());
            return new Reconciliation(rootDomain, pushed, List.of(), List.of());
        }
        List<String> pruned = new ArrayList<>();
        List<String> orphansLeft = new ArrayList<>();
        for (Domain domain : external) {
            for (DnsRecord orphan : orphansUnder(domain, zone)) {
                String what = orphan.type() + " " + orphan.name();
                if (!dnsProperties.pruneOrphans()) {
                    orphansLeft.add(what);
                    continue;
                }
                // Under the name's lock and claimed again inside it. The
                // listing was taken before this loop, so an edit that landed
                // in between has already written the set it owns, and deleting
                // it here would take down a record whose row reads APPLIED.
                Boolean removed = nameLocks.underLock(domain.getFqdn(), () -> {
                    if (claims(domain, orphan)) {
                        return null;
                    }
                    try {
                        provider.remove(orphan.name(), DnsRecordType.valueOf(orphan.type()));
                        return Boolean.TRUE;
                    } catch (DnsProviderException e) {
                        log.error("domain-records reconcile could not prune {}: {}", what,
                                e.getMessage());
                        return Boolean.FALSE;
                    }
                });
                if (removed == null) {
                    log.info("domain-records reconcile: {} was claimed during the scan", what);
                } else if (removed) {
                    pruned.add(what);
                } else {
                    orphansLeft.add(what);
                }
            }
        }
        if (!orphansLeft.isEmpty() && !dnsProperties.pruneOrphans()) {
            log.warn("domain-records reconcile for {}: {} set(s) under an external name that no "
                    + "row claims, pruning is off (PICKLE_DNS_PRUNE_ORPHANS): {}",
                    rootDomain, orphansLeft.size(), orphansLeft);
        }
        log.info("domain-records reconcile for {}: pushed {}, pruned {}, left {}",
                rootDomain, pushed.size(), pruned.size(), orphansLeft.size());
        return new Reconciliation(rootDomain, pushed, pruned, orphansLeft);
    }

    /**
     * The sets the zone holds at or under {@code domain}'s name that no row of
     * that domain claims. Narrowing only: a type an owner cannot write is out
     * of scope whoever put it there, and a name that merely ends in the
     * domain's text without being under it is not the domain's.
     */
    private List<DnsRecord> orphansUnder(Domain domain, List<DnsRecord> zone) {
        Set<String> claimed = new HashSet<>();
        for (DomainRecord record : recordRepository.findByDomainId(domain.getId())) {
            claimed.add(key(record.getName(), record.getType().name()));
        }
        String fqdn = domain.getFqdn();
        List<DnsRecord> orphans = new ArrayList<>();
        for (DnsRecord record : zone) {
            if (!MANAGED_TYPES.contains(record.type())) {
                continue;
            }
            String relative = relativeTo(record.name(), fqdn);
            if (relative == null || claimed.contains(key(relative, record.type()))) {
                continue;
            }
            orphans.add(record);
        }
        return orphans;
    }

    /** Read fresh, inside the lock: does a row of this domain claim that set now. */
    private boolean claims(Domain domain, DnsRecord record) {
        String relative = relativeTo(record.name(), domain.getFqdn());
        if (relative == null) {
            return false;
        }
        Boolean claimed = transactionTemplate.execute(tx ->
                recordRepository.findByDomainId(domain.getId()).stream()
                        .anyMatch(row -> row.getName().equals(relative)
                                && row.getType().name().equals(record.type())));
        return Boolean.TRUE.equals(claimed);
    }

    /** Whether any row of this domain is still owed a write, read fresh. */
    private boolean owesTheZone(long domainId) {
        Boolean owed = transactionTemplate.execute(tx ->
                recordRepository.findByDomainId(domainId).stream()
                        .anyMatch(record -> record.getStatus() != DomainRecordStatus.APPLIED));
        return Boolean.TRUE.equals(owed);
    }

    /**
     * {@code name} expressed relative to {@code fqdn}, or null when it is not
     * under it. The empty string is the domain's own name.
     */
    static String relativeTo(String name, String fqdn) {
        if (name.equals(fqdn)) {
            return "";
        }
        String suffix = "." + fqdn;
        return name.endsWith(suffix) ? name.substring(0, name.length() - suffix.length()) : null;
    }

    private static String key(String name, String type) {
        return name + "/" + type;
    }
}
