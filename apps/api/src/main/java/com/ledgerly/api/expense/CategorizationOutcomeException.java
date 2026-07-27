package com.ledgerly.api.expense;

/**
 * Categorization could not be completed at all — `ai` unavailable, a malformed response, no
 * category taxonomy configured yet, or `ai` chose a category outside the given list. The document
 * stays {@code EXTRACTED}; no expense or ledger row is written.
 */
public class CategorizationOutcomeException extends RuntimeException {

  public CategorizationOutcomeException(String message) {
    super(message);
  }
}
