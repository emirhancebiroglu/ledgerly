package com.ledgerly.api.policy;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of an uploaded policy document: {@code PENDING → PROCESSING → EMBEDDED | FAILED}.
 *
 * <p>Mirrors {@link com.ledgerly.api.document.DocumentStatus}: the legal transitions live here so
 * every caller is held to the same rules, and the terminal states never silently retry into a
 * different outcome.
 */
public enum PolicyDocumentStatus {
  PENDING,
  PROCESSING,
  EMBEDDED,
  FAILED;

  private static final Map<PolicyDocumentStatus, Set<PolicyDocumentStatus>> ALLOWED_TRANSITIONS =
      Map.of(
          PENDING, EnumSet.of(PROCESSING, FAILED),
          PROCESSING, EnumSet.of(EMBEDDED, FAILED),
          EMBEDDED, Collections.emptySet(),
          FAILED, Collections.emptySet());

  public boolean canTransitionTo(PolicyDocumentStatus target) {
    return target != null && ALLOWED_TRANSITIONS.get(this).contains(target);
  }

  public boolean isTerminal() {
    return ALLOWED_TRANSITIONS.get(this).isEmpty();
  }
}
