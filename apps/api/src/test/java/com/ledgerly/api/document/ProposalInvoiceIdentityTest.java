package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Mirrors V27's backfill UPDATE rules — see {@link ProposalInvoiceIdentity}'s class Javadoc for
 * why both exist. */
class ProposalInvoiceIdentityTest {

  @Test
  void invoiceNumberPassesThroughATrimmedRealValue() {
    assertThat(ProposalInvoiceIdentity.invoiceNumber("  INV-2026-042  ")).isEqualTo("INV-2026-042");
  }

  @Test
  void invoiceNumberIsNullForNull() {
    assertThat(ProposalInvoiceIdentity.invoiceNumber(null)).isNull();
  }

  @Test
  void invoiceNumberIsNullForBlankOrWhitespaceOnly() {
    assertThat(ProposalInvoiceIdentity.invoiceNumber("")).isNull();
    assertThat(ProposalInvoiceIdentity.invoiceNumber("   ")).isNull();
  }

  @Test
  void issueDateParsesAWellFormedIsoDate() {
    assertThat(ProposalInvoiceIdentity.issueDate("2026-07-12")).isEqualTo(LocalDate.of(2026, 7, 12));
  }

  @Test
  void issueDateIsNullForNull() {
    assertThat(ProposalInvoiceIdentity.issueDate(null)).isNull();
  }

  @Test
  void issueDateIsNullForNonIsoText() {
    assertThat(ProposalInvoiceIdentity.issueDate("07/12/2026")).isNull();
    assertThat(ProposalInvoiceIdentity.issueDate("not a date")).isNull();
  }

  @Test
  void issueDateIsNullForAShapeMatchingButInvalidCalendarDate() {
    // Matches \d{4}-\d{2}-\d{2} but there is no month 13 or day 45 — must not throw.
    assertThat(ProposalInvoiceIdentity.issueDate("2026-13-45")).isNull();
  }
}
