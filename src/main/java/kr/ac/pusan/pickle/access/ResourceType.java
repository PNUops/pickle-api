package kr.ac.pusan.pickle.access;

/**
 * What kind of thing an access grant is attached to (DB enum {@code resource_type}).
 *
 * <p>Each resource belongs to a workspace and uses resource access grants.
 * The type selects the resource adapter used to resolve its owner and grants.
 */
public enum ResourceType {
    VM("VM"),
    LLM_API_KEY("LLM API 키"),
    DOMAIN("도메인"),
    GPU("GPU");

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
