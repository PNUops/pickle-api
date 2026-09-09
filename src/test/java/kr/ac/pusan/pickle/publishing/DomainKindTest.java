package kr.ac.pusan.pickle.publishing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The three questions a domain kind has to answer before the publishing paths
 * can route it, pinned per value.
 *
 * <p>This is a tripwire, not a restatement of the enum. All three answers used
 * to be derived by excluding CUSTOM, which meant a kind added later inherited
 * "the platform serves this name", "this name counts against the per-VM cap"
 * and "this name is held for its owner after release" without anyone deciding
 * any of them. The expectations are keyed by every value, so adding one fails
 * here until its row is written, and the failure arrives at the table rather
 * than at a user whose records were overwritten with the proxy address.</p>
 *
 * <p>It cannot make the compiler demand those decisions — a new value compiles
 * fine. What it can do is refuse to be green until someone has written down
 * what the value answers, which is the step where the four call sites get
 * looked at.</p>
 */
class DomainKindTest {

    private record Answers(boolean servedByPlatformProxy, boolean countsAgainstPerVmCap,
            boolean reservesNameAfterRelease) {
    }

    private static final Map<DomainKind, Answers> EXPECTED = new EnumMap<>(Map.of(
            // Legacy generated subdomains: no new row is written, but the live
            // ones are served, held their VM's slot when they were created, and
            // sit in the same shared name space.
            DomainKind.AUTO, new Answers(true, true, true),
            DomainKind.PLATFORM, new Answers(true, true, true),
            // The user's own zone, the user's own certificate, and a name
            // nobody else can take, so nothing to hold after release.
            DomainKind.CUSTOM, new Answers(false, false, false)));

    @Test
    void everyKindHasItsAnswers() {
        assertThat(EXPECTED.keySet()).containsExactlyInAnyOrder(DomainKind.values());
    }

    @Test
    void kindsAnswerAsPinned() {
        for (DomainKind kind : DomainKind.values()) {
            Answers expected = EXPECTED.get(kind);
            // Named rather than dereferenced, so a value added without a row
            // reads as the missing decision it is instead of a null pointer.
            assertThat(expected).as("%s has no pinned answers", kind).isNotNull();
            assertThat(kind.servedByPlatformProxy())
                    .as("%s served by the platform proxy", kind)
                    .isEqualTo(expected.servedByPlatformProxy());
            assertThat(SubdomainPolicy.CAPPED_KINDS.contains(kind))
                    .as("%s counts against the per-VM cap", kind)
                    .isEqualTo(expected.countsAgainstPerVmCap());
            assertThat(kind.reservesNameAfterRelease())
                    .as("%s holds its name after release", kind)
                    .isEqualTo(expected.reservesNameAfterRelease());
        }
    }
}
