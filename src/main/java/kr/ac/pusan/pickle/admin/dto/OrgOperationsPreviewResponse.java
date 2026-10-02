package kr.ac.pusan.pickle.admin.dto;

import java.util.List;

public record OrgOperationsPreviewResponse(
        AdminOrgOperationsResponse before,
        AdminOrgOperationsResponse after,
        boolean createsStaffVacancy,
        List<String> warnings) {
}
