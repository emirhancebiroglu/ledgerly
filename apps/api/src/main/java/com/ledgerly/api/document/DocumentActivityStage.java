package com.ledgerly.api.document;

/** Ordered user-visible stages of the document agent pipeline. */
public enum DocumentActivityStage {
  UPLOADED,
  EXTRACTING,
  CATEGORIZING,
  DRAFTING_LEDGER,
  POSTED,
  NEEDS_REVIEW,
  EXTRACTION_NEEDS_REVIEW,
  FAILED,
  CATEGORIZATION_FAILED;

  public boolean isTerminal() {
    return this == POSTED
        || this == NEEDS_REVIEW
        || this == EXTRACTION_NEEDS_REVIEW
        || this == FAILED
        || this == CATEGORIZATION_FAILED;
  }
}
