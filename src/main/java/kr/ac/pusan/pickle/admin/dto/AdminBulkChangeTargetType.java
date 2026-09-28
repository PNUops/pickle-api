package kr.ac.pusan.pickle.admin.dto;

import kr.ac.pusan.pickle.access.ResourceType;

/** What kind of thing a bulk change is aimed at (contract enum {@code AdminBulkChangeTargetType}). */
public enum AdminBulkChangeTargetType {
    LLM_KEY(ResourceType.LLM_API_KEY),
    VM(ResourceType.VM),
    DOMAIN(ResourceType.DOMAIN),
    GPU_ALLOCATION(ResourceType.GPU);

    private final ResourceType resourceType;

    AdminBulkChangeTargetType(ResourceType resourceType) {
        this.resourceType = resourceType;
    }

    /** The access-list type behind this target, for the access change. */
    public ResourceType resourceType() {
        return resourceType;
    }
}
