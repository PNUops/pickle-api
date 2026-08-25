package kr.ac.pusan.pickle.profile.dto;

/**
 * Contract: one entry of the 직책 catalogue (GET /meta/profile-options).
 *
 * <p>{@code requiresStudentNo} is published rather than left for the console to
 * derive from the code. The rule is enforced server-side (bean validation plus
 * the {@code chk_users_student_no} CHECK), and a console that re-derived it
 * would need a release of its own every time a position is added just to stay
 * in agreement.
 */
public record PositionView(String code, String label, boolean requiresStudentNo) {
}
