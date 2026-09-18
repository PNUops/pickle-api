package kr.ac.pusan.pickle.workspace;

/**
 * Ownership workspace kind (contract schema {@code WorkspaceKind}).
 *
 * <p>Classification and display only: no permission, quota, period or approval
 * path reads this. The axis is who runs the space, not what the activity is
 * called, so a department-run competition follows the department while a team
 * entering one on its own is a {@link #COMPETITION}.
 *
 * <p>Declaration order is not display order — the console owns that rule.
 *
 * <p>{@link #TEAM} is retired: it can no longer be created (it is absent from
 * {@link CreatableWorkspaceKind}) and V126 moved the rows that carried it to
 * {@link #PROJECT}. The value stays because PostgreSQL cannot drop an enum
 * label, and reading a row that still holds one would throw without it.
 */
public enum WorkspaceKind {
    /** Created at signup, owned by one person, never deleted and never reclassified. */
    PERSONAL,
    /** Retired — use {@link #PROJECT}. */
    TEAM,
    /** A group building something that none of the other kinds describes. */
    PROJECT,
    /** A credit-bearing course. */
    COURSE,
    /** An educational programme that carries no credit. */
    PROGRAM,
    /** A research lab or research group. */
    LAB,
    /** A student society. */
    CLUB,
    /** A team entered in a contest or hackathon. */
    COMPETITION,
    /** A self-organised study group. */
    STUDY
}
