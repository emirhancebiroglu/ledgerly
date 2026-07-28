package com.ledgerly.api.budget;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record BudgetRequest(
    @NotNull UUID categoryId,
    @NotBlank @Pattern(regexp = "[1-9]\\d{3}-(0[1-9]|1[0-2])") String period,
    @Positive long limitMinor,
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {}
