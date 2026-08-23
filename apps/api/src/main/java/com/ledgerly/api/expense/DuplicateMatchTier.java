package com.ledgerly.api.expense;

/** How confidently {@link DuplicateMatcher} believes two expenses are the same invoice. */
public enum DuplicateMatchTier {
  /** Same organization, same vendor, same invoice number — the invoice number is the accounting
   * document's own identifier, so a match here is treated as certain regardless of amount. */
  CONFIRMED,
  /** No invoice number was readable on either expense; matched instead on vendor + amount +
   * currency within a narrow issue-date window — plausible, not certain (a genuine recurring
   * charge can coincidentally satisfy this). */
  SUSPECTED
}
