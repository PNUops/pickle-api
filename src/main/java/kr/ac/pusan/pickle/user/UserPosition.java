package kr.ac.pusan.pickle.user;

/**
 * 직책 — what the account holder is at the university (contract schema
 * {@code UserPosition}). Collected at signup and by {@code PUT /me/profile}.
 *
 * <p>The Korean label lives here rather than in the console because the
 * console renders whatever {@code GET /meta/profile-options} sends: one home
 * for the wording means the two cannot drift apart.
 *
 * <p>{@link #requiresStudentNo()} is published on the same endpoint for the
 * same reason. The rule is enforced server-side (bean validation plus the
 * {@code chk_users_student_no} CHECK); if the console re-derived it from the
 * enum name, adding a position would need a console release to stay correct.
 *
 * <p>Note this is not a role: authorization never reads it. These values
 * describe a person, not a permission, and nothing here should be read as one:
 * a student position grants exactly what {@code UserRole.USER} grants.
 */
public enum UserPosition {

    STUDENT_UNDERGRAD("학부생", true),
    STUDENT_GRADUATE("대학원생", true),
    RESEARCHER("연구원", false),
    PROFESSOR("교수", false),
    STAFF("직원", false),
    OTHER("기타", false);

    private final String label;
    private final boolean requiresStudentNo;

    UserPosition(String label, boolean requiresStudentNo) {
        this.label = label;
        this.requiresStudentNo = requiresStudentNo;
    }

    public String label() {
        return label;
    }

    /** Whether a 학번 is mandatory for this position. */
    public boolean requiresStudentNo() {
        return requiresStudentNo;
    }
}
