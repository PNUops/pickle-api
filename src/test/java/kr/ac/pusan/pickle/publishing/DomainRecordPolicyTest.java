package kr.ac.pusan.pickle.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.config.PublishingProperties;
import kr.ac.pusan.pickle.publishing.DomainRecordPolicy.DesiredSet;
import kr.ac.pusan.pickle.publishing.dns.DnsRecordType;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What an owner may put in the platform's zone.
 *
 * <p>Written as refusals, because the value of this class is what it turns
 * away. Each case below is a way of reaching something the platform must not
 * publish, and several of them look like ordinary records until read twice.
 * No Spring context: the rules are arithmetic and string work.</p>
 */
class DomainRecordPolicyTest {

    private DomainRecordPolicy policy;

    @BeforeEach
    void setUp() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.stringList(any())).thenReturn(List.of("pusan.dev"));
        policy = new DomainRecordPolicy(
                new PublishingProperties("164.125.249.87", null, null, null),
                settings);
    }

    /**
     * A routable address, deliberately not one of the RFC 5737 documentation
     * ranges this project uses for examples everywhere else. Those ranges are
     * refused here on purpose, so the usual placeholder cannot stand in for a
     * value that is supposed to pass.
     */
    private static final String PUBLIC_V4 = "93.184.216.34";

    @Test
    void anOrdinaryExternalTargetIsAccepted() {
        assertThat(errors(set("", DnsRecordType.A, PUBLIC_V4))).isEmpty();
        assertThat(errors(set("www", DnsRecordType.CNAME, "pages.github.io."))).isEmpty();
        assertThat(errors(set("_acme-challenge", DnsRecordType.TXT, "token-abc"))).isEmpty();
        assertThat(errors(set("", DnsRecordType.AAAA, "2606:4700::1111"))).isEmpty();
    }

    @Test
    void nothingMayPointAtThisPlatform() {
        // The proxy itself, and the campus block around it. The block is the
        // one that matters: a name under a university root aimed at another
        // machine on that network is what a convincing phishing page needs.
        assertThat(messages(set("", DnsRecordType.A, "164.125.249.87")))
                .anyMatch(m -> m.contains("플랫폼이 응답하는 주소"));
        assertThat(messages(set("", DnsRecordType.A, "164.125.18.138")))
                .anyMatch(m -> m.contains("플랫폼이 응답하는 주소"));
        assertThat(messages(set("", DnsRecordType.A, "52.78.104.182")))
                .anyMatch(m -> m.contains("플랫폼이 응답하는 주소"));
        // A CNAME reaches the same place by name rather than by address.
        assertThat(messages(set("www", DnsRecordType.CNAME, "other.pusan.dev")))
                .anyMatch(m -> m.contains("플랫폼이 관리하는 이름"));
    }

    @Test
    void nothingMayNameAnAddressThatIsNotADestination() {
        for (String value : List.of("10.0.0.1", "192.168.1.1", "172.16.0.1", "127.0.0.1",
                "169.254.1.1", "100.64.0.1", "0.0.0.0", "224.0.0.1", "198.18.0.1",
                "192.0.2.5", "203.0.113.5")) {
            assertThat(messages(set("", DnsRecordType.A, value)))
                    .as("A %s", value)
                    .anyMatch(m -> m.contains("도달할 수 있는 주소가 아닙니다"));
        }
        for (String value : List.of("::1", "fd00::1", "fe80::1", "2001:db8::1")) {
            assertThat(messages(set("", DnsRecordType.AAAA, value)))
                    .as("AAAA %s", value)
                    .anyMatch(m -> m.contains("도달할 수 있는 주소가 아닙니다"));
        }
    }

    @Test
    void anIpv4AddressWrappedAsIpv6IsRefused() {
        // The bypass worth naming: ::ffff:10.0.0.1 reads as IPv6 and is not.
        // Every v4 rule above would be skipped if this were taken at face
        // value, and a resolver hands the result to clients as a v6 address.
        assertThat(messages(set("", DnsRecordType.AAAA, "::ffff:10.0.0.1")))
                .anyMatch(m -> m.contains("IPv6 형식으로 감싼"));
        assertThat(messages(set("", DnsRecordType.AAAA, "::ffff:164.125.249.87")))
                .anyMatch(m -> m.contains("IPv6 형식으로 감싼"));
    }

    @Test
    void mailPolicyTextIsRefused() {
        // No MX is offered, which is not the same as being safe: a permissive
        // SPF at a name under a university root makes mail from that name pass
        // a receiving check.
        for (String value : List.of("v=spf1 +all", "v=spf1 -all", "v=DMARC1; p=none",
                "v=STSv1; id=1", "v=DKIM1; k=rsa; p=AAAA")) {
            assertThat(messages(set("", DnsRecordType.TXT, value)))
                    .as("TXT %s", value)
                    .anyMatch(m -> m.contains("메일 정책"));
        }
    }

    @Test
    void aCnameSharesItsNameWithNothing() {
        List<FieldValidationError> errors = errors(
                set("www", DnsRecordType.CNAME, "pages.github.io."),
                set("www", DnsRecordType.A, PUBLIC_V4));
        assertThat(messages(errors)).anyMatch(m -> m.contains("CNAME과 다른 종류를 함께"));
    }

    @Test
    void theSameNameAndTypeMayNotBeGivenTwice() {
        List<FieldValidationError> errors = errors(
                set("www", DnsRecordType.A, PUBLIC_V4),
                set("www", DnsRecordType.A, "93.184.216.35"));
        assertThat(messages(errors)).anyMatch(m -> m.contains("두 번 지정"));
    }

    @Test
    void namesAreLowerCaseAndRelative() {
        assertThat(messages(set("WWW", DnsRecordType.A, "203.0.113.10")))
                .anyMatch(m -> m.contains("소문자"));
        assertThat(messages(set("www.pusan.dev.", DnsRecordType.A, "203.0.113.10")))
                .anyMatch(m -> m.contains("소문자"));
        assertThat(errors(set("", DnsRecordType.A, PUBLIC_V4))).isEmpty();
    }

    @Test
    void boundsAreEnforced() {
        assertThat(messages(new DesiredSet("", DnsRecordType.A, List.of(PUBLIC_V4), 30)))
                .anyMatch(m -> m.contains("TTL"));
        assertThat(messages(new DesiredSet("", DnsRecordType.A, List.of(PUBLIC_V4), 90000)))
                .anyMatch(m -> m.contains("TTL"));

        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add("v" + i);
        }
        assertThat(messages(new DesiredSet("", DnsRecordType.TXT, eleven, 300)))
                .anyMatch(m -> m.contains("최대"));

        List<DesiredSet> many = new ArrayList<>();
        for (int i = 0; i <= DomainRecordPolicy.MAX_SETS_PER_DOMAIN; i++) {
            many.add(new DesiredSet("n" + i, DnsRecordType.A, List.of(PUBLIC_V4), 300));
        }
        assertThat(messages(errors(many))).anyMatch(m -> m.contains("도메인당 최대"));
    }

    @Test
    void aHostNameIsNeverResolvedToDecideAnAddress() {
        // The value is parsed as a literal only. Resolving it would let a
        // record's content choose what this server looks up, and would answer
        // for a name rather than for the address being published.
        assertThat(messages(set("", DnsRecordType.A, "localhost")))
                .anyMatch(m -> m.contains("올바른 IP 주소가 아닙니다"));
        assertThat(messages(set("", DnsRecordType.A, "example.com")))
                .anyMatch(m -> m.contains("올바른 IP 주소가 아닙니다"));
    }

    @Test
    void aCnameIntoTheCampusIsRefused() {
        // The address rules refuse the campus range because a university name
        // aimed at another machine on that network is what a phishing page
        // looks like. A name reaches the same place, and the target is never
        // resolved here, so an address rule cannot see it.
        assertThat(messages(set("www", DnsRecordType.CNAME, "victim.pusan.ac.kr")))
                .anyMatch(m -> m.contains("교내"));
        assertThat(messages(set("www", DnsRecordType.CNAME, "PUSAN.AC.KR.")))
                .anyMatch(m -> m.contains("교내"));
        assertThat(messages(set("www", DnsRecordType.CNAME, "cse.pnu.app")))
                .anyMatch(m -> m.contains("교내"));
    }

    @Test
    void aTargetOutsideTheCampusIsAccepted() {
        assertThat(errors(set("www", DnsRecordType.CNAME, "pages.github.io."))).isEmpty();
        // A name that merely ends in the same letters is not under it.
        assertThat(errors(set("www", DnsRecordType.CNAME, "notpusan.ac.kr.example.com")))
                .isEmpty();
    }

    @Test
    void aValueIsStoredTheWayItWasValidated() {
        DesiredSet normalized = new DesiredSet(" WWW ", DnsRecordType.CNAME,
                List.of(" Pages.GitHub.IO. "), 300).normalized();

        // Validation ran on a stripped and folded copy while the raw string was
        // stored and pushed, so a leading space passed every rule here and then
        // failed at the provider for as long as the row existed.
        assertThat(normalized.name()).isEqualTo("www");
        assertThat(normalized.rrdatas()).containsExactly("pages.github.io.");

        // TXT keeps its case and inner spacing, which are data there.
        DesiredSet text = new DesiredSet("_acme-challenge", DnsRecordType.TXT,
                List.of("  Token With Spaces  "), 300).normalized();
        assertThat(text.rrdatas()).containsExactly("Token With Spaces");
    }

    private static DesiredSet set(String name, DnsRecordType type, String value) {
        return new DesiredSet(name, type, List.of(value), 300);
    }

    private List<FieldValidationError> errors(DesiredSet... sets) {
        return errors(List.of(sets));
    }

    private List<FieldValidationError> errors(List<DesiredSet> sets) {
        List<FieldValidationError> errors = new ArrayList<>();
        policy.validate(sets, "records", errors);
        return errors;
    }

    private List<String> messages(DesiredSet set) {
        return messages(errors(set));
    }

    private static List<String> messages(List<FieldValidationError> errors) {
        return errors.stream().map(FieldValidationError::message).toList();
    }
}
