package kr.ac.pusan.pickle.access;

/**
 * The names one resource type's access-list edits go into the audit trail
 * under.
 *
 * <p>The trail is read by target type and action, and those strings outlive the
 * code that writes them, so they are stated per type rather than derived from
 * an enum name that a later rename could quietly change.
 *
 * @param targetType  the audited target's type, e.g. {@code vm}
 * @param grantAdd    an entry added to the list
 * @param grantUpdate an entry's rung changed
 * @param grantRemove an entry taken off the list
 * @param breakGlass  the extra record left when a workspace owner's edit is
 *                    what puts them inside the resource
 */
public record ResourceAccessAudit(String targetType, String grantAdd, String grantUpdate,
        String grantRemove, String breakGlass) {

    /** The action name for one kind of edit. */
    public String actionOf(GrantChange change) {
        return switch (change) {
            case ADD -> grantAdd;
            case UPDATE -> grantUpdate;
            case REMOVE -> grantRemove;
        };
    }
}
