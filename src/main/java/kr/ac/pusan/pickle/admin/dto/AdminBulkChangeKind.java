package kr.ac.pusan.pickle.admin.dto;

import java.util.EnumSet;
import java.util.Set;

/**
 * The one thing a bulk change does to every target (contract enum
 * {@code AdminBulkChangeKind}). Each kind names the target types it accepts;
 * a request pairing a kind with any other type is refused before a single
 * target is looked at.
 */
public enum AdminBulkChangeKind {
    LLM_KEY_LIMITS(EnumSet.of(AdminBulkChangeTargetType.LLM_KEY)),
    LLM_KEY_STATUS(EnumSet.of(AdminBulkChangeTargetType.LLM_KEY)),
    LLM_KEY_EXPIRY(EnumSet.of(AdminBulkChangeTargetType.LLM_KEY)),
    VM_PERIOD(EnumSet.of(AdminBulkChangeTargetType.VM)),
    VM_POWER(EnumSet.of(AdminBulkChangeTargetType.VM)),
    VM_DELETION(EnumSet.of(AdminBulkChangeTargetType.VM)),
    DOMAIN_RENEWAL(EnumSet.of(AdminBulkChangeTargetType.DOMAIN)),
    DOMAIN_FORCE_RELEASE(EnumSet.of(AdminBulkChangeTargetType.DOMAIN)),
    DOMAIN_VERIFY(EnumSet.of(AdminBulkChangeTargetType.DOMAIN)),
    ACCESS(EnumSet.allOf(AdminBulkChangeTargetType.class));

    private final Set<AdminBulkChangeTargetType> targetTypes;

    AdminBulkChangeKind(Set<AdminBulkChangeTargetType> targetTypes) {
        this.targetTypes = Set.copyOf(targetTypes);
    }

    public boolean accepts(AdminBulkChangeTargetType targetType) {
        return targetTypes.contains(targetType);
    }
}
