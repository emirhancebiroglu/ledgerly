package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The trust boundary, tested at a fixed instant so the date rules are deterministic. */
class ExtractionProposalValidatorTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 7, 27);
  private static final long CEILING_MINOR = 100_000_000L;

  private final ExtractionProposalValidator validator =
      new ExtractionProposalValidator(
          Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC),
          CEILING_MINOR,
          10,
          1);

  @Test
  void aConsistentProposalPasses() {
    ProposalValidationResult result = validator.validate(proposal().build());

    assertThat(result.isValid()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"XXX", "ABC", "eur", "EU", "EURO", "", "ZZZ"})
  void rejectsAnUnknownCurrency(String currency) {
    ProposalValidationResult result = validator.validate(proposal().currency(currency).build());

    assertThat(result.isValid()).isFalse();
    assertThat(result.summary()).contains("currency");
  }

  @Test
  void rejectsANullCurrency() {
    assertThat(validator.validate(proposal().currency(null).build()).isValid()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"EUR", "USD", "GBP", "TRY"})
  void acceptsTheAllowedCurrencies(String currency) {
    assertThat(validator.validate(proposal().currency(currency).build()).isValid()).isTrue();
  }

  @Test
  void rejectsATotalThatIsOffByASingleMinorUnitHigh() {
    // lines 10000 + tax 2100 = 12100; claiming 12101 must fail.
    ProposalValidationResult result = validator.validate(proposal().totalMinor(12101).build());

    assertThat(result.isValid()).isFalse();
    assertThat(result.summary()).contains("does not equal");
  }

  @Test
  void rejectsATotalThatIsOffByASingleMinorUnitLow() {
    ProposalValidationResult result = validator.validate(proposal().totalMinor(12099).build());

    assertThat(result.isValid()).isFalse();
    assertThat(result.summary()).contains("does not equal");
  }

  @Test
  void acceptsAnUnitemizedProposalWhenTheInvoiceLevelAmountsArePlausible() {
    ProposalValidationResult result = validator.validate(proposal().lines(List.of()).build());

    assertThat(result.isValid()).isTrue();
  }

  @Test
  void rejectsAnUnitemizedProposalWhoseTaxExceedsTheTotal() {
    ProposalValidationResult result =
        validator.validate(proposal().lines(List.of()).totalMinor(100).taxMinor(101).build());

    assertThat(result.isValid()).isFalse();
    assertThat(result.summary()).contains("Tax magnitude exceeds");
  }

  @Test
  void rejectsATotalTaxSignMismatch() {
    assertThat(validator.validate(proposal().taxMinor(-1).totalMinor(9999).build()).isValid())
        .isFalse();
  }

  @Test
  void acceptsAZeroTotalDocumentWithOffsettingLinesAndNoTax() {
    ProposalValidationResult result =
        validator.validate(
            proposal()
                .lines(
                    List.of(
                        new ExtractionProposal.Line("service", 1000L, 2_000L),
                        new ExtractionProposal.Line("full discount", 1000L, -2_000L)))
                .taxMinor(0)
                .totalMinor(0)
                .build());

    assertThat(result.isValid()).isTrue();
  }

  @Test
  void rejectsALineWhoseSignDisagreesWithTheTotal() {
    assertThat(
            validator
                .validate(
                    proposal()
                        .lines(List.of(new ExtractionProposal.Line("refund", 1000L, -5000L)))
                        .totalMinor(-2900)
                        .build())
                .isValid())
        .isFalse();
  }

  @Test
  void acceptsAConsistentRefundWhereEverythingIsNegative() {
    ProposalValidationResult result =
        validator.validate(
            proposal()
                .lines(List.of(new ExtractionProposal.Line("refund", 1000L, -5000L)))
                .taxMinor(-100)
                .totalMinor(-5100)
                .build());

    assertThat(result.isValid()).isTrue();
  }

  @Test
  void rejectsARefundCeilingByMagnitudeNotSign() {
    long overCeilingNegative = -(CEILING_MINOR + 1);
    ProposalValidationResult result =
        validator.validate(
            proposal()
                .lines(List.of(new ExtractionProposal.Line("big refund", 1000L, overCeilingNegative)))
                .taxMinor(0)
                .totalMinor(overCeilingNegative)
                .build());

    assertThat(result.isValid()).isFalse();
    assertThat(result.summary()).contains("ceiling");
  }

  @Test
  void rejectsLineAmountsThatOverflowWhenSummed() {
    ProposalValidationResult result =
        validator.validate(
            proposal()
                .lines(
                    List.of(
                        new ExtractionProposal.Line("a", 1000L, Long.MAX_VALUE),
                        new ExtractionProposal.Line("b", 1000L, Long.MAX_VALUE)))
                .build());

    assertThat(result.isValid()).isFalse();
  }

  @Test
  void rejectsADateFarInTheFuture() {
    ProposalValidationResult result =
        validator.validate(proposal().documentDate(TODAY.plusYears(1)).build());

    assertThat(result.isValid()).isFalse();
    assertThat(result.summary()).contains("future");
  }

  @Test
  void rejectsADateJustBeyondTheFutureTolerance() {
    assertThat(validator.validate(proposal().documentDate(TODAY.plusDays(2)).build()).isValid())
        .isFalse();
  }

  @Test
  void acceptsADateWithinTheFutureTolerance() {
    assertThat(validator.validate(proposal().documentDate(TODAY.plusDays(1)).build()).isValid())
        .isTrue();
  }

  @Test
  void rejectsADateAbsurdlyFarInThePast() {
    ProposalValidationResult result =
        validator.validate(proposal().documentDate(LocalDate.of(1970, 1, 1)).build());

    assertThat(result.isValid()).isFalse();
    assertThat(result.summary()).contains("past");
  }

  @Test
  void acceptsADateJustInsideThePastBoundary() {
    assertThat(
            validator.validate(proposal().documentDate(TODAY.minusYears(10).plusDays(1)).build())
                .isValid())
        .isTrue();
  }

  @Test
  void rejectsAMissingDate() {
    assertThat(validator.validate(proposal().documentDate(null).build()).isValid()).isFalse();
  }

  @Test
  void rejectsAnAmountOverTheOrganizationCeiling() {
    long overCeiling = CEILING_MINOR + 1;
    ProposalValidationResult result =
        validator.validate(
            proposal()
                .lines(List.of(new ExtractionProposal.Line("big", 1000L, overCeiling)))
                .taxMinor(0)
                .totalMinor(overCeiling)
                .build());

    assertThat(result.isValid()).isFalse();
    assertThat(result.summary()).contains("ceiling");
  }

  @Test
  void acceptsAnAmountExactlyAtTheCeiling() {
    ProposalValidationResult result =
        validator.validate(
            proposal()
                .lines(List.of(new ExtractionProposal.Line("big", 1000L, CEILING_MINOR)))
                .taxMinor(0)
                .totalMinor(CEILING_MINOR)
                .build());

    assertThat(result.isValid()).isTrue();
  }

  @Test
  void reportsEveryViolationNotJustTheFirst() {
    ProposalValidationResult result =
        validator.validate(
            proposal()
                .currency("XXX")
                .totalMinor(999_999_999_9L)
                .documentDate(TODAY.plusYears(5))
                .build());

    assertThat(result.violations()).hasSizeGreaterThanOrEqualTo(3);
  }

  @Test
  void aNullProposalIsNotValid() {
    assertThat(validator.validate(null).isValid()).isFalse();
  }

  private ProposalBuilder proposal() {
    return new ProposalBuilder();
  }

  /** Builds an otherwise-valid proposal so each test varies exactly one thing. */
  private static final class ProposalBuilder {
    private String currency = "EUR";
    private long totalMinor = 12_100L;
    private long taxMinor = 2_100L;
    private LocalDate documentDate = TODAY.minusDays(3);
    private List<ExtractionProposal.Line> lines =
        List.of(
            new ExtractionProposal.Line("item a", 1000L, 4_000L),
            new ExtractionProposal.Line("item b", 1000L, 6_000L));

    ProposalBuilder currency(String value) {
      this.currency = value;
      return this;
    }

    ProposalBuilder totalMinor(long value) {
      this.totalMinor = value;
      return this;
    }

    ProposalBuilder taxMinor(long value) {
      this.taxMinor = value;
      return this;
    }

    ProposalBuilder documentDate(LocalDate value) {
      this.documentDate = value;
      return this;
    }

    ProposalBuilder lines(List<ExtractionProposal.Line> value) {
      this.lines = value;
      return this;
    }

    ExtractionProposal build() {
      return new ExtractionProposal(
          UUID.randomUUID().toString(),
          "Vendor",
          currency,
          totalMinor,
          taxMinor,
          documentDate,
          lines,
          Map.of("currency", 0.99, "total_minor", 0.95),
          "fake-llm-v1",
          List.of(),
          null);
    }
  }

  @Test
  void theFixedClockMakesTheseAssertionsDeterministic() {
    assertThat(LocalDate.now(Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC)))
        .isEqualTo(TODAY);
  }
}
