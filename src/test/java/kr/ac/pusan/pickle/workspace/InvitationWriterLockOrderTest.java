package kr.ac.pusan.pickle.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The order recipients named by 학번 are locked in. A 학번 claim takes its
 * account's email lock before the 학번 one, so a submission that takes them
 * the other way round could wait on a claim that waits on it.
 */
class InvitationWriterLockOrderTest {

    @Test
    void emailsAreLockedBeforeStudentNumbersAndEachIsSortedAndFolded() {
        assertThat(InvitationWriter.lockOrder(
                List.of("Zed@pusan.ac.kr", "amy@pusan.ac.kr"),
                List.of("209900002", "ab-209900001", "AB-209900001")))
                .containsExactly("e:amy@pusan.ac.kr", "e:zed@pusan.ac.kr",
                        "s:209900002", "s:AB-209900001");
    }

    @Test
    void nothingToLockIsAnEmptyOrder() {
        assertThat(InvitationWriter.lockOrder(List.of(), List.of())).isEmpty();
    }
}
