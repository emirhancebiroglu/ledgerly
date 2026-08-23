package com.ledgerly.api.document;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** Extracts the invoice-identity fields (M9.6 T1) from a raw {@code document.proposal} JSONB
 * value. Mirrors {@code V27__expense_invoice_identity.sql}'s backfill UPDATE — kept as a single
 * Java source of truth so the exact same blank/malformed-input rules are both applied to new
 * postings (via {@link ProposalInvoiceIdentity#invoiceNumber}/{@link
 * ProposalInvoiceIdentity#issueDate}) and unit-testable, rather than only living inside SQL a
 * test can't directly exercise. The migration's own SQL implements the same rules over legacy
 * rows at backfill time; if either rule changes, the other must change with it. */
public final class ProposalInvoiceIdentity {

  private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

  private ProposalInvoiceIdentity() {}

  /** {@code null}, blank, or whitespace-only becomes {@code null} — never an empty string. */
  public static String invoiceNumber(String rawInvoiceNumber) {
    if (rawInvoiceNumber == null) {
      return null;
    }
    String trimmed = rawInvoiceNumber.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** {@code null} or anything that isn't a well-formed, valid ISO-8601 date (e.g. {@code
   * "2026-13-45"}, which matches the shape but is not a real date) becomes {@code null} rather
   * than throwing. */
  public static LocalDate issueDate(String rawDocumentDate) {
    if (rawDocumentDate == null || !ISO_DATE.matcher(rawDocumentDate).matches()) {
      return null;
    }
    try {
      return LocalDate.parse(rawDocumentDate);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
