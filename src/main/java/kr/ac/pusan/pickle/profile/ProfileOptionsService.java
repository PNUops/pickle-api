package kr.ac.pusan.pickle.profile;

import java.util.Arrays;
import java.util.List;
import kr.ac.pusan.pickle.profile.dto.PositionView;
import kr.ac.pusan.pickle.profile.dto.ProfileOptionsResponse;
import kr.ac.pusan.pickle.user.UserPosition;
import org.springframework.stereotype.Service;

/**
 * Serves the two catalogues a profile is built from, and is the single place
 * that decides whether a submitted pair of 직책·소속 is one the platform knows.
 */
@Service
public class ProfileOptionsService {

    private final DepartmentCatalog departments;

    public ProfileOptionsService(DepartmentCatalog departments) {
        this.departments = departments;
    }

    public ProfileOptionsResponse options() {
        return new ProfileOptionsResponse(positions(), departments.all());
    }

    /** Declaration order of the enum, which is the display order. */
    public List<PositionView> positions() {
        return Arrays.stream(UserPosition.values())
                .map(position -> new PositionView(position.name(), position.label(),
                        position.requiresStudentNo()))
                .toList();
    }

    public boolean isKnownDepartment(String code) {
        return departments.isKnown(code);
    }

    public String departmentName(String code) {
        return departments.nameOf(code);
    }
}
