package com.ledgerly.api.document;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The trust boundary. Nothing an agent proposes reaches the ledger without passing every rule here.
 *
 * <p>This class exists before any real model does, deliberately: validation written after the model
 * it validates gets shaped around that model's particular bugs instead of around the specification
 * (see the "LLM is never on the write path" ADR). The question is never whether the model is wrong,
 * only what a wrong answer costs — behind this gate it costs a review-queue item.
 *
 * <p>Every rule is checked, and all violations are returned together.
 */
@Component
public class ExtractionProposalValidator {

  /**
   * Currencies this deployment will accept. An ISO code that merely exists is not enough — an
   * organization that has never traded in it is far more likely a misread than a real invoice.
   */
  private static final Set<String> ALLOWED_CURRENCIES = Set.of("EUR", "USD", "GBP", "TRY");

  private final Clock clock;
  private final long amountCeilingMinor;
  private final int maxAgeYears;
  private final int maxFutureDays;

  public ExtractionProposalValidator(
      Clock clock,
      @Value("${ledgerly.extraction.amount-ceiling-minor:100000000}") long amountCeilingMinor,
      @Value("${ledgerly.extraction.max-age-years:10}") int maxAgeYears,
      @Value("${ledgerly.extraction.max-future-days:1}") int maxFutureDays) {
    this.clock = clock;
    this.amountCeilingMinor = amountCeilingMinor;
    this.maxAgeYears = maxAgeYears;
    this.maxFutureDays = maxFutureDays;
  }

  public ProposalValidationResult validate(ExtractionProposal proposal) {
    List<String> violations = new ArrayList<>();

    if (proposal == null) {
      return new ProposalValidationResult(List.of("Proposal is missing"));
    }

    checkCurrency(proposal, violations);
    checkArithmetic(proposal, violations);
    checkDate(proposal, violations);
    checkCeiling(proposal, violations);

    return new ProposalValidationResult(violations);
  }

  private void checkCurrency(ExtractionProposal proposal, List<String> violations) {
    String currency = proposal.currency();
    if (currency == null || !ALLOWED_CURRENCIES.contains(currency)) {
      violations.add("Unknown or unsupported currency: " + currency);
      return;
    }
    try {
      Currency.getInstance(currency);
    } catch (IllegalArgumentException notAnIsoCode) {
      violations.add("Unknown or unsupported currency: " + currency);
    }
  }

  /**
   * The books balance or the proposal does not pass. An off-by-one-minor-unit discrepancy is a
   * failure exactly like any other: a cent that cannot be accounted for is a cent that will have to
   * be reconciled by hand later.
   */
  private void checkArithmetic(ExtractionProposal proposal, List<String> violations) {
    if (proposal.lines() == null || proposal.lines().isEmpty()) {
      violations.add("Proposal has no line items to reconcile against the total");
      return;
    }
    if (proposal.taxMinor() < 0) {
      violations.add("Tax is negative: " + proposal.taxMinor());
    }
    if (proposal.totalMinor() < 0) {
      violations.add("Total is negative: " + proposal.totalMinor());
    }
    if (proposal.lines().stream().anyMatch(line -> line.amountMinor() < 0)) {
      violations.add("A line amount is negative");
    }

    long expected;
    try {
      expected = Math.addExact(proposal.lineTotalMinor(), proposal.taxMinor());
    } catch (ArithmeticException overflow) {
      violations.add("Line amounts overflow when summed");
      return;
    }

    if (expected != proposal.totalMinor()) {
      violations.add(
          "Total %d does not equal lines %d + tax %d (differs by %d minor units)"
              .formatted(
                  proposal.totalMinor(),
                  proposal.lineTotalMinor(),
                  proposal.taxMinor(),
                  proposal.totalMinor() - expected));
    }
  }

  /**
   * A date far in the future or absurdly far in the past is a misread, not an invoice. One day of
   * future tolerance covers a document issued across a timezone boundary.
   */
  private void checkDate(ExtractionProposal proposal, List<String> violations) {
    LocalDate documentDate = proposal.documentDate();
    if (documentDate == null) {
      violations.add("Document date is missing");
      return;
    }
    LocalDate today = LocalDate.now(clock);
    if (documentDate.isAfter(today.plusDays(maxFutureDays))) {
      violations.add("Document date is in the future: " + documentDate);
    }
    if (documentDate.isBefore(today.minus(Period.ofYears(maxAgeYears)))) {
      violations.add("Document date is implausibly far in the past: " + documentDate);
    }
  }

  /**
   * An amount over the organization's ceiling routes to a human even when everything else about the
   * proposal is consistent — a plausible-looking extraction is exactly how a misplaced decimal gets
   * posted without anyone noticing.
   */
  private void checkCeiling(ExtractionProposal proposal, List<String> violations) {
    if (proposal.totalMinor() > amountCeilingMinor) {
      violations.add(
          "Total %d exceeds the organization ceiling of %d minor units"
              .formatted(proposal.totalMinor(), amountCeilingMinor));
    }
  }

  /** Exposed for diagnostics and tests; the set itself is immutable. */
  public Set<String> allowedCurrencies() {
    return ALLOWED_CURRENCIES.stream().collect(Collectors.toUnmodifiableSet());
  }
}
