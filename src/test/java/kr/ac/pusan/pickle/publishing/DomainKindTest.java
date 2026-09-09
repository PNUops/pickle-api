package kr.ac.pusan.pickle.publishing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The two questions a domain kind has to answer before the publishing paths can
 * route it, pinned per value.
 *
 * <p>This is a tripwire, not a restatement of the enum. Both answers used to be
 * derived by excluding CUSTOM, which meant a kind added later inherited "the
 * platform serves this name" and "this name counts against the per-VM cap"
 * without anyone deciding either. The expectations below are keyed by every
 * value, so adding one fails here until its row is written, and the failure
 * arrives at the table rather than at a user whose records were overwritten
 * with the proxy address.</p>
 */
class DomainKindTest {

    private record Answers(boolean servedByPlatformProxy, boolean countsAgainstPerVmCap) {
    }

    private static final Map<DomainKind, Answers> EXPECTED = new EnumMap<>(Map.of(
            // Legacy generated subdomains: no new row is written, but the live
            // ones are served and held their VM's slot when they were created.
            DomainKind.AUTO, new Answers(true, true),
            DomainKind.PLATFORM, new Answers(true, true),
            // The user's own zone and the user's own certificate.
            DomainKind.CUSTOM, new Answers(false, false)));

    @Test
    void everyKindHasBothAnswers() {
        assertThat(EXPECTED.keySet()).containsExactlyInAnyOrder(DomainKind.values());
    }

    @Test
    void kindsAnswerAsPinned() {
        for (DomainKind kind : DomainKind.values()) {
            Answers expected = EXPECTED.get(kind);
            assertThat(kind.servedByPlatformProxy())
                    .as("%s served by the platform proxy", kind)
                    .isEqualTo(expected.servedByPlatformProxy());
            assertThat(SubdomainPolicy.CAPPED_KINDS.contains(kind))
                    .as("%s counts against the per-VM cap", kind)
                    .isEqualTo(expected.countsAgainstPerVmCap());
        }
    }
}
