package com.ledgerly.api.expense;

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
   * idempotency filter cannot dedup) from both posting a ledger transaction: a read-then-write
   * split — even one guarded by {@code SELECT ... FOR UPDATE} — leaves a window where both
   * requests' row lock is granted in sequence but each still observes the pre-transition status
   * in its already-fetched Java object, so both proceed. Folding the {@code WHERE status =
   * 'NEEDS_REVIEW'} guard into the {@code UPDATE} itself closes that window regardless of lock
   * timing: only the first writer's statement can match the row, and the second gets 0 rows back
   * no matter how the two transactions interleaved.
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

  List<Expense> findByOrganizationId(UUID organizationId, Pageable pageable);

  List<Expense> findByOrganizationIdAndStatus(
      UUID organizationId, ExpenseStatus status, Pageable pageable);

  List<Expense> findByOrganizationIdAndVendorIgnoreCaseContaining(
      UUID organizationId, String vendor, Pageable pageable);

  List<Expense> findByOrganizationIdAndStatusAndVendorIgnoreCaseContaining(
      UUID organizationId, ExpenseStatus status, String vendor, Pageable pageable);
}
