package com.ledgerly.api.document;

/** Ordered user-visible stages of the document agent pipeline. */
public enum DocumentActivityStage {
  UPLOADED,
  EXTRACTING,
  CATEGORIZING,
  DRAFTING_LEDGER,
  POSTED,
  NO_POSTING_REQUIRED,
  NEEDS_REVIEW,
  EXTRACTION_NEEDS_REVIEW,
  FAILED,
  CATEGORIZATION_FAILED;

  public boolean isTerminal() {
    return this == POSTED
        || this == NO_POSTING_REQUIRED
        || this == NEEDS_REVIEW
        || this == EXTRACTION_NEEDS_REVIEW
        || this == FAILED
        || this == CATEGORIZATION_FAILED;
  }
}
