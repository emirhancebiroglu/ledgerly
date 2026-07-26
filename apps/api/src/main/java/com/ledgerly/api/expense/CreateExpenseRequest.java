package com.ledgerly.api.expense;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateExpenseRequest(@Positive long amountMinor, @NotBlank String currency) {}
