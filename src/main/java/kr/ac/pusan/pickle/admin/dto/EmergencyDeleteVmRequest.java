package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Contract op {@code emergencyDeleteVm} body: {@code confirmName} must equal
 * the VM's {@code name} exactly (409 {@code VM_CONFIRM_NAME_MISMATCH} otherwise).
 */
public record EmergencyDeleteVmRequest(@NotBlank String confirmName) {
}
