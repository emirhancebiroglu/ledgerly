package com.ledgerly.api.expense;

/**
 * Terminal outcome of an expense's categorization: {@code POSTED} if confidence cleared the
 * threshold and a ledger transaction was built, {@code NEEDS_REVIEW} otherwise. Both are terminal
 * — there is no automatic re-categorization; a review-queue item is resolved by a human action
 * (dashboard work, M7), not a status transition this enum needs to model.
 */
public enum ExpenseStatus {
  POSTED,
  NEEDS_REVIEW
}
