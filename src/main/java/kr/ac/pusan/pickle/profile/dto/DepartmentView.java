package kr.ac.pusan.pickle.profile.dto;

/** Contract: one entry of the 소속 catalogue (GET /meta/profile-options). */
public record DepartmentView(String code, String college, String name) {
}
