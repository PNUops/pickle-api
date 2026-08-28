package kr.ac.pusan.pickle.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The catalogue's loading rules, none of which had a test.
 *
 * <p>The override path was a hard-coded absolute constant, so nothing could
 * point at it and nothing could check what happens when the file is there. That
 * matters more than the usual testability argument: this class was already
 * corrected once for falling back to the packaged list when the file existed
 * but could not be opened, which is a substitution with no symptom other than
 * an edit that did nothing. A rule nothing can check is a rule that can quietly
 * come back.
 */
class DepartmentCatalogLoadTest {

    @Test
    void noOverrideMeansThePackagedList(@TempDir Path dir) {
        DepartmentCatalog catalog = new DepartmentCatalog(dir.resolve("absent.json"));
        assertThat(catalog.isKnown("COMPUTER_SCIENCE")).isTrue();
        assertThat(catalog.isKnown(DepartmentCatalog.OTHER)).isTrue();
    }

    @Test
    void anOverrideReplacesThePackagedListEntirely(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                {"departments":[
                  {"code":"ONE","name":"한 학과","college":"단과대"},
                  {"code":"OTHER","name":"기타","college":"기타"}
                ]}""");
        DepartmentCatalog catalog = new DepartmentCatalog(file);
        assertThat(catalog.isKnown("ONE")).isTrue();
        // Not merged with the shipped list: an operator who removes a department
        // has removed it.
        assertThat(catalog.isKnown("COMPUTER_SCIENCE")).isFalse();
        assertThat(catalog.nameOf("ONE")).isEqualTo("한 학과");
    }

    @Test
    void malformedJsonRefusesStartup(@TempDir Path dir) throws IOException {
        Path file = write(dir, "{\"departments\":[");
        assertThatThrownBy(() -> new DepartmentCatalog(file))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("could not be read as JSON");
    }

    @Test
    void somethingPresentButUnopenableRefusesStartup(@TempDir Path dir) throws IOException {
        // The correction this class exists for. Keyed on existence, not
        // readability: classifying an unopenable path as absent serves a list
        // the operator believes they replaced, and the only symptom is that
        // the edit did nothing.
        //
        // A directory rather than a mode-000 file, because the permission
        // version does not hold everywhere: root ignores the mode bits, so on
        // the deploy host the file stays readable and the case never fires.
        // Opening a directory as a stream fails for every user, which is the
        // same branch reached by a means that does not depend on who is running.
        Path asDirectory = Files.createDirectory(dir.resolve("departments.json"));
        assertThatThrownBy(() -> new DepartmentCatalog(asDirectory))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("check permissions and syntax");
    }

    @Test
    void aModeZeroFileRefusesStartupWhereTheModeIsHonoured(@TempDir Path dir) throws IOException {
        // The literal permission case, skipped where it cannot be staged.
        // Running as root makes the file readable no matter what the mode says,
        // and a test that silently passes because its premise never held is
        // worse than one that says it did not run.
        Path file = write(dir, "{\"departments\":[{\"code\":\"OTHER\",\"name\":\"기타\",\"college\":\"기타\"}]}");
        assertThat(file.toFile().setReadable(false)).isTrue();
        try {
            assumeTrue(!Files.isReadable(file), "이 사용자에게는 모드 비트가 적용되지 않는다");
            assertThatThrownBy(() -> new DepartmentCatalog(file))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("check permissions and syntax");
        } finally {
            file.toFile().setReadable(true);
        }
    }

    @Test
    void aDuplicateCodeRefusesStartup(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                {"departments":[
                  {"code":"DUP","name":"첫째","college":"단과대"},
                  {"code":"DUP","name":"둘째","college":"단과대"},
                  {"code":"OTHER","name":"기타","college":"기타"}
                ]}""");
        assertThatThrownBy(() -> new DepartmentCatalog(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate code");
    }

    @Test
    void anOverrideWithoutTheFallbackRefusesStartup(@TempDir Path dir) throws IOException {
        // OTHER is what a student whose 학과 is unlisted picks. Without it that
        // student has no way to answer 소속 at all.
        Path file = write(dir, """
                {"departments":[{"code":"ONE","name":"한 학과","college":"단과대"}]}""");
        assertThatThrownBy(() -> new DepartmentCatalog(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DepartmentCatalog.OTHER);
    }

    @Test
    void theUnknownCodeStandsInForItsOwnName(@TempDir Path dir) {
        // What a stored code does when the catalogue has moved on without it.
        DepartmentCatalog catalog = new DepartmentCatalog(dir.resolve("absent.json"));
        assertThat(catalog.nameOf("DEPARTMENT_THAT_CLOSED")).isEqualTo("DEPARTMENT_THAT_CLOSED");
    }

    private static Path write(Path dir, String json) throws IOException {
        Path file = dir.resolve("departments.json");
        Files.writeString(file, json);
        return file;
    }
}
