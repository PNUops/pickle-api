package kr.ac.pusan.pickle.workspace.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;

public record ConfirmTerminationRequest(@Valid @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TerminationConfirmation confirmation) { }
