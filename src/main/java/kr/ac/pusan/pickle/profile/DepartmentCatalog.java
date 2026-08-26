package kr.ac.pusan.pickle.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.profile.dto.DepartmentView;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
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

    /** Always present, always last: the fallback for an unlisted 소속. */
    public static final String OTHER = "OTHER";

    private final Map<String, DepartmentView> byCode;

    public DepartmentCatalog() {
        this.byCode = load();
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

    private static Map<String, DepartmentView> load() {
        Catalog catalog;
        try (InputStream in = new ClassPathResource("departments.json").getInputStream()) {
            catalog = JsonMapper.builder().build().readValue(in, Catalog.class);
        } catch (IOException e) {
            throw new UncheckedIOException("departments.json is not readable", e);
        }
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

    /** Shape of the resource file; the leading {@code _comment} block is not data. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Catalog(List<DepartmentView> departments) {
    }
}
