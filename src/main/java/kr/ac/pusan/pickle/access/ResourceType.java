package kr.ac.pusan.pickle.access;

/**
 * What kind of thing an access grant is attached to (DB enum {@code resource_type}).
 *
 * <p>Only VMs are wired to the access list today. Containers and LLM API keys
 * are decided additions that pose the same question — one object, its own set
 * of people — and each joins by adding a value here plus the adapter described
 * in the authorization design.
 */
public enum ResourceType {
    VM
}
