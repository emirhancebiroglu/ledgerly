package com.ledgerly.api.budget;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {

  Optional<Budget> findByIdAndOrganizationId(UUID id, UUID organizationId);

  Optional<Budget> findByOrganizationIdAndCategoryIdAndPeriodAndCurrency(
      UUID organizationId, UUID categoryId, String period, String currency);

  List<Budget> findByOrganizationId(UUID organizationId, Pageable pageable);

  boolean existsByOrganizationIdAndCategoryIdAndPeriodAndCurrency(
      UUID organizationId, UUID categoryId, String period, String currency);

  boolean existsByOrganizationIdAndCategoryIdAndPeriodAndCurrencyAndIdNot(
      UUID organizationId, UUID categoryId, String period, String currency, UUID id);

  /** Locks one budget row so two posting transactions cannot emit its threshold alert twice. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT b FROM Budget b WHERE b.organizationId = :organizationId "
          + "AND b.categoryId = :categoryId AND b.period = :period AND b.currency = :currency")
  Optional<Budget> findForEvaluation(
      @Param("organizationId") UUID organizationId,
      @Param("categoryId") UUID categoryId,
      @Param("period") String period,
      @Param("currency") String currency);
}
