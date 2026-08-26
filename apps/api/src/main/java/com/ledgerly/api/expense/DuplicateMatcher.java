package com.ledgerly.api.expense;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Two-layer duplicate detector (M9.6): an exact match on invoice number is {@link
 * DuplicateMatchTier#CONFIRMED}; absent that, a vendor + amount + currency match within a narrow
 * issue-date window is {@link DuplicateMatchTier#SUSPECTED}. Never mutates anything — the caller
 * decides what, if anything, to do with a match. */
@Component
public class DuplicateMatcher {

  /** Inclusive days on each side of {@code candidate.issueDate} the heuristic searches — wide
   * enough to catch a re-uploaded copy of the same invoice a few days apart, narrow enough that a
   * genuine monthly recurring charge (~30 days apart) does not fall inside it. */
  private static final long WINDOW_DAYS = 7;

  private final ExpenseRepository expenseRepository;

  public DuplicateMatcher(ExpenseRepository expenseRepository) {
    this.expenseRepository = expenseRepository;
  }

  /** Finds the best duplicate match for {@code candidate}, if any. {@code candidate} must already
   * be persisted (its {@code id} excludes it from its own search) but is not required to be
   * committed for reads in the same transaction to see it. */
  public Optional<DuplicateMatch> findDuplicate(Expense candidate) {
    String vendorKey = candidate.getVendorKey();
    if (vendorKey == null) {
      return Optional.empty();
    }

    if (candidate.getInvoiceNumber() != null) {
      List<Expense> confirmed =
          expenseRepository.findConfirmedDuplicateCandidates(
              candidate.getOrganizationId(), candidate.getId(), vendorKey, candidate.getInvoiceNumber());
      if (!confirmed.isEmpty()) {
        return Optional.of(new DuplicateMatch(confirmed.get(0).getId(), DuplicateMatchTier.CONFIRMED));
      }
      // A real invoice number on the candidate is a strong, deliberate identifier — if it does
      // not exactly match an earlier expense, the amount-based heuristic must not silently take
      // over and produce a false SUSPECTED match instead.
      return Optional.empty();
    }

    if (candidate.getIssueDate() == null) {
      return Optional.empty();
    }
    List<Expense> suspected =
        expenseRepository.findSuspectedDuplicateCandidates(
            candidate.getOrganizationId(),
            candidate.getId(),
            vendorKey,
            candidate.getCurrency(),
            candidate.getAmountMinor(),
            candidate.getIssueDate().minus(WINDOW_DAYS, ChronoUnit.DAYS),
            candidate.getIssueDate().plus(WINDOW_DAYS, ChronoUnit.DAYS));
    return suspected.isEmpty()
        ? Optional.empty()
        : Optional.of(new DuplicateMatch(suspected.get(0).getId(), DuplicateMatchTier.SUSPECTED));
  }
}
