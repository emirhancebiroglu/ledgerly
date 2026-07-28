package com.ledgerly.api.expense;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Body of {@code POST /expenses/{id}/correct} — the human's replacement category. */
public record CorrectExpenseRequest(@NotNull UUID categoryId) {}
