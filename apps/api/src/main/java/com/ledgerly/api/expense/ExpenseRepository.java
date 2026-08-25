package com.ledgerly.api.expense;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

  Optional<Expense> findByIdAndOrganizationId(UUID id, UUID organizationId);

  /**
   * Resolves a {@code NEEDS_REVIEW} expense to {@code POSTED} in one atomic statement, returning
   * the number of rows changed (0 or 1). This is what actually prevents two concurrent
   * resolutions (approve/correct hit with different {@code Idempotency-Key} values, which the M3
   * idempotency filter cannot dedup) from both posting a ledger transaction. An earlier version
   * used {@code SELECT ... FOR UPDATE} plus a separate in-memory status check instead; a
   * concurrency test proved that insufficient — not because the lock itself failed to serialize
   * the two transactions, but because the status check ran against a Java object already held in
   * the caller's persistence context, not a value read fresh after the lock was granted. Folding
   * {@code WHERE status = 'NEEDS_REVIEW'} into the {@code UPDATE} itself removes that dependency
   * entirely: Postgres re-evaluates the whole {@code WHERE} clause against the row's committed
   * state before a blocked writer proceeds, so only the first writer's statement can ever match,
   * and the second gets 0 rows back no matter how the two transactions interleaved.
   *
   * <p>{@code clearAutomatically}: the caller loads this same {@code Expense} both before and
   * after this call, and a bulk {@code UPDATE} bypasses Hibernate's persistence context — without
   * clearing it, the post-call read would return the same stale, pre-transition instance from the
   * first-level cache instead of hitting the database.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE Expense e SET e.categoryId = :categoryId, e.ledgerTransactionId = :ledgerTransactionId, "
          + "e.status = com.ledgerly.api.expense.ExpenseStatus.POSTED "
          + "WHERE e.id = :id AND e.organizationId = :organizationId "
          + "AND e.status = com.ledgerly.api.expense.ExpenseStatus.NEEDS_REVIEW")
  int resolveIfNeedsReview(
      @Param("id") UUID id,
      @Param("organizationId") UUID organizationId,
      @Param("categoryId") UUID categoryId,
      @Param("ledgerTransactionId") UUID ledgerTransactionId);

  Optional<Expense> findByDocumentIdAndOrganizationId(UUID documentId, UUID organizationId);

  /**
   * Inserts the durable fallback for a categorization outcome exactly once. PostgreSQL's existing
   * unique {@code document_id} constraint, rather than a read-then-write race, owns duplicate
   * delivery safety.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO expense (id, organization_id, document_id, vendor, category_id, "
              + "ledger_transaction_id, amount_minor, currency, categorization_confidence, "
              + "citation, status, created_at) "
              + "VALUES (:id, :organizationId, :documentId, :vendor, NULL, NULL, :amountMinor, "
              + ":currency, 0, NULL, 'NEEDS_REVIEW', CURRENT_TIMESTAMP) "
              + "ON CONFLICT (document_id) DO NOTHING",
      nativeQuery = true)
  int insertUnclassifiedNeedsReview(
      @Param("id") UUID id,
      @Param("organizationId") UUID organizationId,
      @Param("documentId") UUID documentId,
      @Param("vendor") String vendor,
      @Param("amountMinor") long amountMinor,
      @Param("currency") String currency);

  List<Expense> findByOrganizationId(UUID organizationId, Pageable pageable);

  long countByOrganizationId(UUID organizationId);

  List<Expense> findByOrganizationIdAndStatus(
      UUID organizationId, ExpenseStatus status, Pageable pageable);

  List<Expense> findByOrganizationIdAndVendorIgnoreCaseContaining(
      UUID organizationId, String vendor, Pageable pageable);

  List<Expense> findByOrganizationIdAndStatusAndVendorIgnoreCaseContaining(
      UUID organizationId, ExpenseStatus status, String vendor, Pageable pageable);

  /** Exact duplicate candidates: same organization, same vendor (case/whitespace-insensitive),
   * same invoice number, excluding the candidate itself. Most recent first so a caller taking the
   * first result gets a deterministic, newest-first choice among several matches. */
  @Query(
      "SELECT e FROM Expense e WHERE e.organizationId = :organizationId "
          + "AND e.id <> :excludingId "
          + "AND LOWER(TRIM(e.vendor)) = :vendorKey "
          + "AND e.invoiceNumber = :invoiceNumber "
          + "ORDER BY e.createdAt DESC")
  List<Expense> findConfirmedDuplicateCandidates(
      @Param("organizationId") UUID organizationId,
      @Param("excludingId") UUID excludingId,
      @Param("vendorKey") String vendorKey,
      @Param("invoiceNumber") String invoiceNumber);

  /** Heuristic duplicate candidates for when neither expense has a readable invoice number: same
   * organization, vendor and currency, {@code issueDate} within the given inclusive window.
   * Windowing is done in SQL (not by loading every same-vendor expense into Java) since an
   * organization can accumulate years of postings for one recurring vendor. */
  @Query(
      "SELECT e FROM Expense e WHERE e.organizationId = :organizationId "
          + "AND e.id <> :excludingId "
          + "AND LOWER(TRIM(e.vendor)) = :vendorKey "
          + "AND e.currency = :currency "
          + "AND e.amountMinor = :amountMinor "
          + "AND e.issueDate BETWEEN :windowStart AND :windowEnd "
          + "ORDER BY e.createdAt DESC")
  List<Expense> findSuspectedDuplicateCandidates(
      @Param("organizationId") UUID organizationId,
      @Param("excludingId") UUID excludingId,
      @Param("vendorKey") String vendorKey,
      @Param("currency") String currency,
      @Param("amountMinor") long amountMinor,
      @Param("windowStart") LocalDate windowStart,
      @Param("windowEnd") LocalDate windowEnd);
}
