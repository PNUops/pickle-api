package kr.ac.pusan.pickle.admin.dto;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Minimal account choice and applicable policy copy source for one budget command. */
public record InstitutionLlmAccountReference(UUID id, String name, boolean eligibleForBinding,
        @Nullable List<String> defaultCreditAllowedModels, @Nullable List<String> defaultCreditDeniedModels) { }
