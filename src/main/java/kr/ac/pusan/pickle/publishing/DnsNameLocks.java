package kr.ac.pusan.pickle.publishing;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * One lock per DNS name, shared by everything that writes that name into the
 * platform's zone.
 *
 * <p>Two jobs can want opposite things for one name at once — a release
 * pushing a record's removal and a revive pushing it back, each enqueued after
 * its own commit — and the writes have to be ordered by something. The lock is
 * that something, and it only works if every writer takes the same one: a
 * second map keyed the same way is two locks with one name between them, which
 * serialises nothing.</p>
 *
 * <p>In process, because the api runs as a single instance (the startup
 * advisory lock). A second instance would need this to move to the database,
 * and the name is what it would be keyed on there too.</p>
 */
@Component
public class DnsNameLocks {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T underLock(String fqdn, Supplier<T> step) {
        ReentrantLock lock = locks.computeIfAbsent(fqdn.toLowerCase(Locale.ROOT),
                key -> new ReentrantLock());
        lock.lock();
        try {
            return step.get();
        } finally {
            lock.unlock();
        }
    }
}
