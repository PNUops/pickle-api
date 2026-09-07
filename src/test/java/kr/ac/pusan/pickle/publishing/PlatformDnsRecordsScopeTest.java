package kr.ac.pusan.pickle.publishing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import kr.ac.pusan.pickle.publishing.dns.DnsRecord;
import org.junit.jupiter.api.Test;

/**
 * The one destructive rule in the DNS work, pinned in isolation: which zone
 * records the admin resync may remove. Every branch that narrows the set has
 * a record here that exercises it.
 */
class PlatformDnsRecordsScopeTest {

    private static final String ROOT = "example.dev";
    private static final String PROXY = "203.0.113.10";

    private static DnsRecord record(String name, String type, String... values) {
        return new DnsRecord(name, type, List.of(values), 300);
    }

    @Test
    void onlySingleLabelProxyPointingARecordsNobodyClaimsAreOrphans() {
        List<DnsRecord> zone = List.of(
                record("gone-team.example.dev.", "A", PROXY),          // orphan
                record("GONE-Other.example.dev", "A", PROXY),          // orphan, case-insensitive
                record("live-team.example.dev.", "A", PROXY),          // claimed by a live row
                record("held-team.example.dev.", "A", PROXY),          // claimed by a reserved row
                record("example.dev.", "A", PROXY),                    // apex
                record("*.example.dev.", "A", PROXY),                  // wildcard
                record("deep.team.example.dev.", "A", PROXY),          // two labels down
                record("elsewhere.example.dev.", "A", "198.51.100.7"), // not our address
                record("both.example.dev.", "A", PROXY, "198.51.100.7"), // ours plus another
                record("admin.example.dev.", "A", PROXY),              // reserved label, hand-made
                record("example.dev.", "NS", "ns-cloud-a1.googledomains.com."),
                record("example.dev.", "SOA", "ns-cloud-a1.googledomains.com. dns-admin.google.com. 1 21600 3600 259200 300"),
                record("example.dev.", "CAA", "0 issue \"letsencrypt.org\""),
                record("_pickle-verify.x.example.dev.", "TXT", "\"pv-abc\""),
                record("mail.example.dev.", "MX", "10 mx.example.dev."),
                record("v6.example.dev.", "AAAA", "2001:db8::1"),
                record("alias.example.dev.", "CNAME", "live-team.example.dev."),
                record("other-root.example.dev.example.org.", "A", PROXY), // another zone's name
                record("team.otherexample.dev.", "A", PROXY));           // suffix trap

        List<DnsRecord> orphans = PlatformDnsRecords.orphanCandidates(zone, ROOT,
                Set.of("live-team.example.dev", "held-team.example.dev"),
                Set.of("admin", "www"), PROXY);

        assertThat(orphans).extracting(DnsRecord::name)
                .containsExactly("gone-team.example.dev", "gone-other.example.dev");
    }

    @Test
    void anEmptyZoneOrAZoneWithNothingOfOursYieldsNoOrphans() {
        assertThat(PlatformDnsRecords.orphanCandidates(List.of(), ROOT, Set.of(), Set.of(), PROXY))
                .isEmpty();
        assertThat(PlatformDnsRecords.orphanCandidates(List.of(
                record("example.dev.", "NS", "ns-cloud-a1.googledomains.com."),
                record("*.example.dev.", "A", PROXY)), ROOT, Set.of(), Set.of(), PROXY))
                .isEmpty();
    }
}
