package com.ledgerly.api.document;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of an uploaded document: {@code PENDING ⇄ PROCESSING → EXTRACTED |
 * EXTRACTION_NEEDS_REVIEW |
 * FAILED}.
 *
 * <p>The legal transitions live here rather than in the service so that every caller is held to the
 * same rules. {@code EXTRACTED}, {@code EXTRACTION_NEEDS_REVIEW} and {@code FAILED} are terminal:
 * a document that already failed validation is never silently retried into a different outcome,
 * which would make the audit trail lie about what happened.
 */
public enum DocumentStatus {
  PENDING,
  PROCESSING,
  EXTRACTED,
  EXTRACTION_NEEDS_REVIEW,
  FAILED;

  private static final Map<DocumentStatus, Set<DocumentStatus>> ALLOWED_TRANSITIONS =
      Map.of(
          PENDING, EnumSet.of(PROCESSING, FAILED),
          PROCESSING, EnumSet.of(PENDING, EXTRACTED, EXTRACTION_NEEDS_REVIEW, FAILED),
          EXTRACTED, Collections.emptySet(),
          EXTRACTION_NEEDS_REVIEW, Collections.emptySet(),
          FAILED, Collections.emptySet());

  public boolean canTransitionTo(DocumentStatus target) {
    return target != null && ALLOWED_TRANSITIONS.get(this).contains(target);
  }

  public boolean isTerminal() {
    return ALLOWED_TRANSITIONS.get(this).isEmpty();
  }
}
