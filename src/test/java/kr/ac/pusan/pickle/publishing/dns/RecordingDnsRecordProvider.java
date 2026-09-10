package kr.ac.pusan.pickle.publishing.dns;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An in-memory zone that records every call. Tests seed it, read it back,
 * make it fail on demand and flip it unconfigured; a shared {@code sequence}
 * sink lets a test interleave its calls with the proxy-agent's in one list
 * to assert their order.
 */
public class RecordingDnsRecordProvider implements DnsRecordProvider {

    private final Map<String, DnsRecord> zone = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> sequence;
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final AtomicReference<Runnable> beforeEnsure = new AtomicReference<>();
    private volatile boolean configured = true;

    public RecordingDnsRecordProvider(List<String> sequence) {
        this.sequence = sequence;
    }

    public RecordingDnsRecordProvider() {
        this(new CopyOnWriteArrayList<>());
    }

    @Override
    public boolean configured() {
        return configured;
    }

    // The call log stays keyed by name alone, whatever the type: every existing
    // assertion reads it that way and callsFor() matches on the name suffix.
    // A test that cares which type was written reads the zone instead.

    @Override
    public void ensure(String fqdn, DnsRecordType type, List<String> values, int ttlSeconds) {
        record("ensure", fqdn);
        runBeforeEnsure();
        failIfArmed();
        zone.put(key(fqdn, type.name()),
                new DnsRecord(fqdn, type.name(), values, ttlSeconds));
    }

    @Override
    public void remove(String fqdn, DnsRecordType type) {
        record("remove", fqdn);
        failIfArmed();
        zone.remove(key(fqdn, type.name()));
    }

    @Override
    public List<DnsRecord> listRecords(String rootDomain) {
        record("list", rootDomain);
        failIfArmed();
        return new ArrayList<>(zone.values());
    }

    // ── test controls ─────────────────────────────────────────────────────

    /** Puts a record set into the zone as if someone else had written it. */
    public void seed(String name, String type, List<String> values) {
        zone.put(key(name, type), new DnsRecord(name, type, values, 300));
    }

    /** Takes a record set out of the zone behind the platform's back. */
    public void unseed(String name, String type) {
        zone.remove(key(name, type));
    }

    public boolean hasA(String fqdn) {
        return zone.containsKey(key(fqdn, "A"));
    }

    public DnsRecord a(String fqdn) {
        return zone.get(key(fqdn, "A"));
    }

    public DnsRecord recordSet(String name, DnsRecordType type) {
        return zone.get(key(name, type.name()));
    }

    public boolean has(String name, String type) {
        return zone.containsKey(key(name, type));
    }

    /** Every provider call so far, as {@code ensure:<fqdn>} / {@code remove:<fqdn>} / {@code list:<root>}. */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    public List<String> callsFor(String fqdn) {
        String suffix = ":" + fqdn.toLowerCase(Locale.ROOT);
        return calls.stream().filter(call -> call.endsWith(suffix)).toList();
    }

    /**
     * Runs once inside the next ensure, before the zone is written. Stands in
     * for the window a real provider call occupies: a test uses it to commit
     * something while a push is in flight.
     */
    public void beforeNextEnsure(Runnable action) {
        beforeEnsure.set(action);
    }

    private void runBeforeEnsure() {
        Runnable action = beforeEnsure.getAndSet(null);
        if (action != null) {
            action.run();
        }
    }

    /** Every write from now on throws with this message; null disarms. */
    public void failWith(String message) {
        failure.set(message);
    }

    public void setConfigured(boolean configured) {
        this.configured = configured;
    }

    public void reset() {
        zone.clear();
        calls.clear();
        sequence.clear();
        failure.set(null);
        beforeEnsure.set(null);
        configured = true;
    }

    private void record(String op, String name) {
        String entry = op + ":" + name.toLowerCase(Locale.ROOT);
        calls.add(entry);
        sequence.add("dns:" + entry);
    }

    private void failIfArmed() {
        String message = failure.get();
        if (message != null) {
            throw new DnsProviderException(message);
        }
    }

    private static String key(String name, String type) {
        return DnsNames.relative(name) + "/" + type;
    }
}
