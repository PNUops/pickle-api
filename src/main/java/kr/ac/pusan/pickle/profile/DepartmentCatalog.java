package kr.ac.pusan.pickle.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.profile.dto.DepartmentView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 소속(학과) catalogue, loaded once from {@code departments.json}.
 *
 * <p>It is a classpath resource rather than a table because, unlike a node or
 * an IP pool, this list does not differ between hosts: it is a fact about the
 * university, not about the machine the platform runs on, so there is nothing
 * for an operator to decide and a seeding step would only be a way to forget.
 * (Until v0.46.0 the argument was stronger still — 소속 학과 was a required
 * signup field, so an empty table meant a fresh database could not accept a
 * signup at all. It is optional now, so that half no longer applies.)
 * {@code users.department_code} stores the code and carries no foreign key, so
 * a renamed department needs no data migration.
 *
 * <p>A code that is not in the catalogue is refused at the service layer, and
 * {@link #OTHER} is the safety net for a 소속 학과 the list does not name. A
 * missing or malformed resource fails startup rather than serving an empty
 * list: signup would survive that now, but the profile prompt would offer no
 * choices and there would be nothing on screen to say why.
 */
@Component
public class DepartmentCatalog {

    private static final Logger log = LoggerFactory.getLogger(DepartmentCatalog.class);

    /** Always present, always last: the fallback for an unlisted 소속. */
    public static final String OTHER = "OTHER";

    private final Map<String, DepartmentView> byCode;

    public DepartmentCatalog() {
        this(HOST_OVERRIDE);
    }

    /**
     * The override path, for the tests that exercise the loading rules.
     *
     * <p>A hard-coded absolute path made every one of those rules unreachable:
     * the duplicate-code refusal, the missing-{@code OTHER} refusal, and the
     * refusal to fall back when the file is present but unusable — which is the
     * rule this class was corrected for once already. A constant nothing can
     * point at is a rule nothing can check.
     */
    DepartmentCatalog(Path override) {
        this.byCode = load(override);
    }

    /** Catalogue order, which is the display order the console renders. */
    public List<DepartmentView> all() {
        return List.copyOf(byCode.values());
    }

    public boolean isKnown(String code) {
        return code != null && byCode.containsKey(code);
    }

    /** The 학과 name for a stored code; the code itself when it is unknown. */
    public String nameOf(String code) {
        DepartmentView view = code == null ? null : byCode.get(code);
        return view == null ? code : view.name();
    }

    /**
     * Host override, read before the packaged copy.
     *
     * <p>The list changes when the university reorganises, which is roughly
     * yearly, and putting it in a table with an admin screen to manage it costs
     * a migration, a handful of contract operations and a console page for that.
     * A file on the host is edited and the service restarted. The packaged copy
     * stays the default, so losing the override reverts to the shipped list
     * rather than to nothing — which also keeps it out of the backup set.
     */
    static final Path HOST_OVERRIDE = Path.of("/etc/pickle/departments.json");

    private static Map<String, DepartmentView> load(Path override) {
        Catalog catalog = Files.exists(override) ? readHostOverride(override) : readPackaged();
        Map<String, DepartmentView> map = new LinkedHashMap<>();
        for (DepartmentView department : catalog.departments()) {
            if (map.put(department.code(), department) != null) {
                throw new IllegalStateException("departments.json has a duplicate code: " + department.code());
            }
        }
        if (!map.containsKey(OTHER)) {
            throw new IllegalStateException("departments.json must carry the " + OTHER + " fallback");
        }
        return Collections.unmodifiableMap(map);
    }

    private static Catalog readPackaged() {
        try (InputStream in = new ClassPathResource("departments.json").getInputStream()) {
            return JsonMapper.builder().build().readValue(in, Catalog.class);
        } catch (IOException | JacksonException e) {
            throw new UncheckedIOException("departments.json is not readable", asIo(e));
        }
    }

    /**
     * Reads the host file, and refuses to start if it is there but unusable.
     *
     * <p>Falling back to the packaged list would be the worse failure: the
     * service comes up serving a list the operator believes they replaced, and
     * the only symptom is that an edit did nothing.
     *
     * <p>Keyed on {@code exists}, not {@code isReadable}, and that is the whole
     * point. A file the service cannot open — root-owned 600, a directory it
     * cannot traverse — is the same silent substitution as a malformed one, and
     * {@code isReadable} classifies it as absent. The only signal either way is
     * the success log below, so its absence says nothing.
     */
    private static Catalog readHostOverride(Path override) {
        try (InputStream in = Files.newInputStream(override)) {
            log.info("소속 카탈로그를 호스트 파일에서 읽습니다: {}", override);
            return JsonMapper.builder().build().readValue(in, Catalog.class);
        } catch (IOException | JacksonException e) {
            // Both, and the second is not redundant: a Jackson parse failure is
            // a RuntimeException, not an IOException, so catching IOException
            // alone let a malformed file escape as a raw parser stack trace.
            // Startup still failed — but the one message that says what to look
            // at never reached the operator, which is most of the value here.
            throw new UncheckedIOException(override + " is present but could not be read as JSON"
                    + " (check permissions and syntax)", asIo(e));
        }
    }

    /** {@link UncheckedIOException} insists on an {@link IOException} cause. */
    private static IOException asIo(Exception cause) {
        return cause instanceof IOException io ? io : new IOException(cause);
    }

    /** Shape of the resource file; the leading {@code _comment} block is not data. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Catalog(List<DepartmentView> departments) {
    }
}
