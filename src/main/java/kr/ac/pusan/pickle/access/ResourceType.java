package kr.ac.pusan.pickle.access;

/**
 * What kind of thing an access grant is attached to (DB enum {@code resource_type}).
 *
 * <p>Every resource is owned by a workspace and carries the same access list, so
 * a kind joins by adding a value here plus the adapter described in the
 * authorization design — not by another authorization model. Containers are a
 * decided addition on the same terms.
 */
public enum ResourceType {
    VM("VM"),
    LLM_API_KEY("LLM API 키");

    private final String label;

    ResourceType(String label) {
        this.label = label;
    }

    /**
     * What user-facing text calls this kind of thing. Notifications about the
     * request flow are shared by every type, so the word has to come from the
     * type rather than from the sentence it appears in.
     */
    public String label() {
        return label;
    }
}
