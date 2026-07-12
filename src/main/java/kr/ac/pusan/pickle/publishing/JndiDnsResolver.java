package kr.ac.pusan.pickle.publishing;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * JDK JNDI DNS resolver (docs/plan/06 custom-domain verification). A lookup
 * failure (NXDOMAIN, timeout, SERVFAIL) surfaces as an empty list — the verifier
 * treats "no matching record yet" and "not resolvable" the same way.
 */
@Component
public class JndiDnsResolver implements DnsResolver {

    private static final Logger log = LoggerFactory.getLogger(JndiDnsResolver.class);

    @Override
    public List<String> txtRecords(String name) {
        return lookup(name, "TXT").stream().map(JndiDnsResolver::stripQuotes).toList();
    }

    @Override
    public List<String> aRecords(String name) {
        return lookup(name, "A");
    }

    private List<String> lookup(String name, String type) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        env.put(Context.PROVIDER_URL, "dns:");
        // Bound the lookup: a domain whose NS is a blackhole must not pin a
        // shared JobRunr worker for the JNDI defaults (~15s x lookups).
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "2");
        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            Attributes attributes = ctx.getAttributes(name, new String[] {type});
            Attribute attribute = attributes.get(type);
            if (attribute == null) {
                return List.of();
            }
            List<String> values = new ArrayList<>(attribute.size());
            NamingEnumeration<?> all = attribute.getAll();
            while (all.hasMore()) {
                values.add(String.valueOf(all.next()));
            }
            return values;
        } catch (Exception e) {
            log.debug("DNS {} lookup for {} failed: {}", type, name, e.getMessage());
            return List.of();
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (Exception ignored) {
                    // best-effort close
                }
            }
        }
    }

    /** A multi-string TXT record comes back space-joined and quoted per chunk. */
    private static String stripQuotes(String value) {
        return value.replace("\"", "").strip();
    }
}
