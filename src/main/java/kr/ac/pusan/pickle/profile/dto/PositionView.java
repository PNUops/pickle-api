package kr.ac.pusan.pickle.profile.dto;

/**
 * Contract: one entry of the 직책 catalogue (GET /meta/profile-options).
 *
 * <p>{@code requiresStudentNo} is published rather than left for the console to
 * derive from the code: a console that re-derived it would need a release of
 * its own every time a position is added just to stay in agreement. The rule
 * itself is enforced by {@code ProfileValidator} — see {@code UserPosition}
 * for why the CHECK constraint is not a second line of defence.
 */
public record PositionView(String code, String label, boolean requiresStudentNo) {
}
