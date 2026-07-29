package com.ledgerly.api.expense;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ledgerly.api.document.DocumentResponse;
import com.ledgerly.api.document.DocumentActivityResponse;
import com.ledgerly.api.ledger.LedgerEntryView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/expenses/{id}/detail} — {@link ExpenseResponse}'s fields plus the ledger
 * entries the posting produced and the document metadata behind it, for the expense-detail
 * screen's document viewer, field grid, and ledger-entry rows.
 *
 * @param ledgerEntries empty, not null, for a {@code NEEDS_REVIEW} expense — nothing has posted
 *     yet, and an empty list is the honest representation of that, not a sentinel to special-case.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExpenseDetailResponse(
    UUID id,
    UUID documentId,
    String vendor,
    UUID categoryId,
    UUID ledgerTransactionId,
    long amountMinor,
    String currency,
    double categorizationConfidence,
    String citation,
    ExpenseStatus status,
    Instant createdAt,
    List<LedgerEntryView> ledgerEntries,
    DocumentResponse document,
    String invoiceNumber,
    LocalDate documentDate,
    String taxMinor,
    List<DocumentActivityResponse> activity) {

  public static ExpenseDetailResponse from(
      Expense expense,
      List<LedgerEntryView> ledgerEntries,
      DocumentResponse document,
      ExtractedDocumentFields fields,
      List<DocumentActivityResponse> activity) {
    return new ExpenseDetailResponse(
        expense.getId(),
        expense.getDocumentId(),
        expense.getVendor(),
        expense.getCategoryId(),
        expense.getLedgerTransactionId(),
        expense.getAmountMinor(),
        expense.getCurrency(),
        expense.getCategorizationConfidence(),
        expense.getCitation(),
        expense.getStatus(),
        expense.getCreatedAt(),
        ledgerEntries,
        document,
        fields.invoiceNumber(),
        fields.documentDate(),
        fields.taxMinor(),
        activity);
  }

  /** Read-only fields from the validated extraction proposal; never a second ledger write model. */
  public record ExtractedDocumentFields(
      String invoiceNumber, LocalDate documentDate, String taxMinor) {
    public static ExtractedDocumentFields unavailable() {
      return new ExtractedDocumentFields(null, null, null);
    }
  }
}
