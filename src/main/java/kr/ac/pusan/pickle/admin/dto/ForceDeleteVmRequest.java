package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Contract op {@code forceDeleteVm} body: {@code confirmName} must equal
 * the VM's {@code name} exactly (409 {@code VM_CONFIRM_NAME_MISMATCH} otherwise).
 * {@code overrideProtection} (M6, default false) is the SYS_ADMIN escalation
 * that bypasses {@code deletion_protection} and clears the PVE flag before
 * destroy — recorded in the audit detail.
 */
public record ForceDeleteVmRequest(@NotBlank String confirmName, Boolean overrideProtection) {

    /** Absent/null defaults to false (no protection override). */
    public boolean overridesProtection() {
        return Boolean.TRUE.equals(overrideProtection);
    }
}
