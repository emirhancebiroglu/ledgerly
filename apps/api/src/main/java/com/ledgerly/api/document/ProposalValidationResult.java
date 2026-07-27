package com.ledgerly.api.document;

import java.util.List;

/**
 * Outcome of checking a proposal at the trust boundary.
 *
 * @param violations every rule the proposal broke, not just the first — a reviewer fixing one
 *     problem should not have to re-run to discover the next
 */
public record ProposalValidationResult(List<String> violations) {

  public ProposalValidationResult {
    violations = List.copyOf(violations);
  }

  public static ProposalValidationResult valid() {
    return new ProposalValidationResult(List.of());
  }

  public boolean isValid() {
    return violations.isEmpty();
  }

  /** Human-readable summary, suitable for {@code document.failure_reason}. */
  public String summary() {
    return String.join("; ", violations);
  }
}
