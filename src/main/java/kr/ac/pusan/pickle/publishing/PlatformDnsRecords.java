package kr.ac.pusan.pickle.publishing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import kr.ac.pusan.pickle.config.DnsProperties;
import kr.ac.pusan.pickle.config.PublishingProperties;
import kr.ac.pusan.pickle.publishing.dns.DnsProviderException;
import kr.ac.pusan.pickle.publishing.dns.DnsRecord;
import kr.ac.pusan.pickle.publishing.dns.DnsRecordProvider;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The platform's own A records for platform subdomains: one per serving
 * name, pointing at the reverse proxy, written through the configured
 * {@link DnsRecordProvider}. Custom domains never reach this class — they are
 * the user's zone — and {@link #managed(Domain)} is the single test of that.
 *
 * <p>Every provider call runs outside any database transaction (the same
 * discipline as the proxy-agent call in {@link RouteApplyJob}) and under an
 * in-process lock per FQDN. The lock matters because two jobs can want
 * opposite things for one name at once: a release pushing the record's
 * removal and a revive pushing it back, each enqueued after its own commit.
 * The route generation orders their intents, so each DNS step re-reads the
 * route's generation under the lock right before it calls the provider and
 * skips when a newer intent has been written since; the newer intent's own
 * apply, queued behind the same lock, then writes the record's final state.
 * The api runs as a single instance (the startup advisory lock), which is
 * what makes an in-process lock sufficient.</p>
 */
@Component
public class PlatformDnsRecords {

    private static final Logger log = LoggerFactory.getLogger(PlatformDnsRecords.class);

    /** Result of one provider step. */
    public sealed interface Outcome permits Done, Skipped, Failed {
    }

    /** The provider confirmed the record now matches the desired state. */
    public record Done() implements Outcome {
    }

    /** A newer intent superseded this step before the provider was called. */
    public record Skipped() implements Outcome {
    }

    /** The provider refused or was unreachable; {@code error} is what to record. */
    public record Failed(String error) implements Outcome {
    }

    private final DnsRecordProvider provider;
    private final DnsProperties dnsProperties;
    private final PublishingProperties publishingProperties;
    private final SettingsService settingsService;
    private final DomainRepository domainRepository;
    private final RouteRepository routeRepository;
    private final TransactionTemplate transactionTemplate;
    private final ConcurrentHashMap<String, ReentrantLock> fqdnLocks = new ConcurrentHashMap<>();

    public PlatformDnsRecords(DnsRecordProvider provider, DnsProperties dnsProperties,
            PublishingProperties publishingProperties, SettingsService settingsService,
            DomainRepository domainRepository, RouteRepository routeRepository,
            TransactionTemplate transactionTemplate) {
        this.provider = provider;
        this.dnsProperties = dnsProperties;
        this.publishingProperties = publishingProperties;
        this.settingsService = settingsService;
        this.domainRepository = domainRepository;
        this.routeRepository = routeRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /** Whether a platform subdomain can be given a record at all right now. */
    public boolean configured() {
        return provider.configured();
    }

    /**
     * Whether the platform owns this domain's records: every kind but CUSTOM.
     * A custom domain's zone belongs to its user, so nothing here may ever
     * write to it, whatever state its row is in.
     */
    public static boolean managed(Domain domain) {
        return domain.getKind() != DomainKind.CUSTOM && domain.getRootDomain() != null;
    }

    /** The address every platform record points at: the reverse proxy. */
    public String targetIp() {
        return publishingProperties.proxyPublicIp();
    }

    // ── steps driven by a route's desired state (the apply job) ─────────────

    /**
     * Puts the record up for a PRESENT route, unless the route's generation
     * moved since {@code generation} was read (a newer intent owns the name).
     */
    public Outcome ensureForRoute(String fqdn, long routeId, long generation) {
        return underLock(fqdn, () -> stillCurrent(routeId, generation)
                ? attempt(() -> provider.ensureA(fqdn, targetIp(), ttlSeconds()))
                : new Skipped());
    }

    /**
     * Takes the record down for an ABSENT route, under the same generation
     * guard: a revive written during the vhost removal outranks this step.
     */
    public Outcome removeForRoute(String fqdn, long routeId, long generation) {
        return underLock(fqdn, () -> stillCurrent(routeId, generation)
                ? attempt(() -> provider.removeA(fqdn))
                : new Skipped());
    }

    // ── steps driven by the name alone (sweeper reclaim, admin resync) ──────

    /** Takes the record down for a name that has no route any more. */
    public Outcome remove(String fqdn) {
        return underLock(fqdn, () -> attempt(() -> provider.removeA(fqdn)));
    }

    /** Puts the record up for a name whose serving state the caller has just read. */
    public Outcome ensure(String fqdn) {
        return underLock(fqdn, () -> attempt(() -> provider.ensureA(fqdn, targetIp(), ttlSeconds())));
    }

    // ── admin resync: add what is missing, remove what nothing claims ───────

    /** What one zone reconciliation did, for the log and the tests. */
    public record ZoneReconciliation(String rootDomain, List<String> ensured, List<String> confirmed,
            List<String> failed, List<String> pruned, List<String> orphansLeft) {
    }

    /**
     * Reconciles the zone of every allowed root against the serving platform
     * domains: a serving name whose record is missing or wrong is ensured, a
     * serving name whose record is already right is marked APPLIED without a
     * write, and records the platform could have written but no live domain
     * row claims are pruned — or, unless {@code pickle.dns.prune-orphans} is
     * on, listed at WARN so an operator sees what a prune would take.
     *
     * <p>Never throws: the vhost half of the resync must not depend on the
     * zone being reachable. A root whose zone cannot be listed is logged and
     * skipped whole, since without the listing neither half can be decided.</p>
     */
    public List<ZoneReconciliation> reconcile(List<Domain> servingPlatformDomains) {
        if (!provider.configured()) {
            log.warn("dns reconcile skipped: provider unconfigured");
            return List.of();
        }
        List<ZoneReconciliation> results = new ArrayList<>();
        // Folded to lower case because the labels it is compared against are:
        // a record's name arrives through DnsNames.relative, which lower-cases
        // it. An unfolded reserved list would let a differently-cased reserved
        // label look unreserved, and the prune treats unreserved as deletable.
        Set<String> reserved = settingsService.stringList(SettingsService.RESERVED_SUBDOMAINS).stream()
                .map(label -> label.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        for (String root : settingsService.stringList(SettingsService.ALLOWED_ROOT_DOMAINS)) {
            String rootDomain = root.toLowerCase(Locale.ROOT);
            List<DnsRecord> zone;
            try {
                zone = provider.listRecords(rootDomain);
            } catch (DnsProviderException e) {
                log.error("dns reconcile skipped for {}: {}", rootDomain, e.getMessage());
                continue;
            }
            results.add(reconcileRoot(rootDomain, zone, servingPlatformDomains, reserved));
        }
        return results;
    }

    private ZoneReconciliation reconcileRoot(String rootDomain, List<DnsRecord> zone,
            List<Domain> servingPlatformDomains, Set<String> reserved) {
        Map<String, DnsRecord> aRecords = new HashMap<>();
        for (DnsRecord record : zone) {
            if ("A".equals(record.type())) {
                aRecords.put(record.name(), record);
            }
        }
        List<String> ensured = new ArrayList<>();
        List<String> confirmed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Domain domain : servingPlatformDomains) {
            if (!rootDomain.equals(domain.getRootDomain()) || !managed(domain)) {
                continue;
            }
            String fqdn = domain.getFqdn();
            DnsRecord current = aRecords.get(fqdn);
            boolean right = current != null && current.values().equals(List.of(targetIp()))
                    && current.ttlSeconds() == ttlSeconds();
            // Re-read before writing, not only after. The manifest was taken
            // before the provider calls, so a domain released in between has
            // already had its own ABSENT push remove the record; ensuring it
            // here would put the record back and nothing would take it down
            // again, because writeServingState then declines to record it and
            // the pruner is off by default. Confirming a record that is already
            // right needs no such check: it writes no zone change.
            if (!right && !stillServing(domain.getId())) {
                continue;
            }
            Outcome outcome = right ? new Done() : ensure(fqdn);
            if (outcome instanceof Failed failure) {
                failed.add(fqdn);
                writeServingState(domain.getId(), d -> d.markDnsFailed(failure.error()));
            } else {
                (right ? confirmed : ensured).add(fqdn);
                writeServingState(domain.getId(), Domain::markDnsApplied);
            }
        }
        Set<String> claimed = new HashSet<>();
        for (Domain live : domainRepository.findByRootDomainAndStatusNot(rootDomain,
                DomainStatus.REMOVED)) {
            claimed.add(live.getFqdn());
        }
        List<String> pruned = new ArrayList<>();
        List<String> orphansLeft = new ArrayList<>();
        for (DnsRecord orphan : orphanCandidates(zone, rootDomain, claimed, reserved, targetIp())) {
            if (!dnsProperties.pruneOrphans()) {
                orphansLeft.add(orphan.name());
                continue;
            }
            // Re-read the claim immediately before deleting. The claimed set
            // above was taken once, and a platform name is reissuable the
            // moment its reservation lapses, so a name freed during the scan
            // and taken by someone else in the meantime would have that new
            // owner's freshly written record deleted while their row reads
            // APPLIED. This is the one destructive path here, so it re-checks
            // rather than trusting a snapshot.
            if (domainRepository.findFirstByFqdnAndStatusNot(orphan.name(), DomainStatus.REMOVED)
                    .isPresent()) {
                log.info("dns reconcile: {} was claimed during the scan, not pruning", orphan.name());
                continue;
            }
            if (remove(orphan.name()) instanceof Failed failure) {
                orphansLeft.add(orphan.name());
                log.error("dns reconcile could not prune {}: {}", orphan.name(), failure.error());
            } else {
                pruned.add(orphan.name());
            }
        }
        if (!orphansLeft.isEmpty() && !dnsProperties.pruneOrphans()) {
            log.warn("dns reconcile for {}: {} orphan record(s) left in place, pruning is off "
                    + "(PICKLE_DNS_PRUNE_ORPHANS): {}", rootDomain, orphansLeft.size(), orphansLeft);
        }
        log.info("dns reconcile for {}: ensured {}, confirmed {}, failed {}, pruned {}, left {}",
                rootDomain, ensured.size(), confirmed.size(), failed.size(), pruned.size(),
                orphansLeft.size());
        return new ZoneReconciliation(rootDomain, ensured, confirmed, failed, pruned, orphansLeft);
    }

    /**
     * The zone records the platform may remove: exactly the ones it could
     * have written itself and no longer claims. Every condition narrows, none
     * widens — a record is a candidate only when it is an A record, its owner
     * is a single label directly under {@code rootDomain} (never the apex,
     * never the wildcard, never anything deeper), the label is not one the
     * platform refuses to issue (a reserved label was hand-made by
     * definition), its data is exactly the proxy address, and no live domain
     * row — serving or held in its reservation grace — carries the name.
     * NS, SOA, CAA, TXT, MX and every other type are outside this by the
     * first condition alone.
     */
    static List<DnsRecord> orphanCandidates(List<DnsRecord> zone, String rootDomain,
            Set<String> claimedFqdns, Set<String> reservedLabels, String proxyIp) {
        String suffix = "." + rootDomain.toLowerCase(Locale.ROOT);
        List<DnsRecord> orphans = new ArrayList<>();
        for (DnsRecord record : zone) {
            if (!"A".equals(record.type()) || !record.name().endsWith(suffix)) {
                continue;
            }
            String label = record.name().substring(0, record.name().length() - suffix.length());
            if (label.isEmpty() || label.contains(".") || label.contains("*")
                    || reservedLabels.contains(label)) {
                continue;
            }
            if (!record.values().equals(List.of(proxyIp)) || claimedFqdns.contains(record.name())) {
                continue;
            }
            orphans.add(record);
        }
        return orphans;
    }

    // ── internals ───────────────────────────────────────────────────────────

    private int ttlSeconds() {
        return (int) dnsProperties.recordTtl().toSeconds();
    }

    /** Own short transaction: is the route still at the generation the step was planned for. */
    private boolean stillCurrent(long routeId, long generation) {
        Boolean current = transactionTemplate.execute(tx -> routeRepository.findById(routeId)
                .map(route -> route.getGeneration() == generation).orElse(false));
        return Boolean.TRUE.equals(current);
    }

    /** Whether this domain is still ACTIVE with a live route, read fresh. */
    private boolean stillServing(long domainId) {
        Boolean serving = transactionTemplate.execute(tx -> domainRepository.findById(domainId)
                .filter(domain -> domain.getStatus() == DomainStatus.ACTIVE)
                .filter(domain -> routeRepository
                        .findFirstByDomainIdAndStatusNot(domain.getId(), RouteStatus.REMOVED)
                        .isPresent())
                .isPresent());
        return Boolean.TRUE.equals(serving);
    }

    /**
     * Writes a resync verdict on a domain that is still serving. The manifest
     * was read before the provider calls, so a domain released meanwhile is
     * left alone: its own ABSENT push owns the record from here on. The zone
     * write is guarded separately by {@link #stillServing} — this filter only
     * keeps the row from claiming a state nobody asked for.
     */
    private void writeServingState(long domainId, java.util.function.Consumer<Domain> change) {
        transactionTemplate.executeWithoutResult(tx -> domainRepository.findByIdForUpdate(domainId)
                .filter(domain -> domain.getStatus() == DomainStatus.ACTIVE)
                .filter(domain -> routeRepository
                        .findFirstByDomainIdAndStatusNot(domain.getId(), RouteStatus.REMOVED)
                        .isPresent())
                .ifPresent(change));
    }

    private static Outcome attempt(Runnable call) {
        try {
            call.run();
            return new Done();
        } catch (DnsProviderException e) {
            log.warn("dns step failed: {}", e.getMessage());
            return new Failed(e.getMessage());
        }
    }

    private Outcome underLock(String fqdn, Supplier<Outcome> step) {
        ReentrantLock lock = fqdnLocks.computeIfAbsent(fqdn.toLowerCase(Locale.ROOT),
                key -> new ReentrantLock());
        lock.lock();
        try {
            return step.get();
        } finally {
            lock.unlock();
        }
    }
}
