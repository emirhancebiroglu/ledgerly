package com.ledgerly.api.budget;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {

  Optional<Budget> findByIdAndOrganizationId(UUID id, UUID organizationId);

  List<Budget> findByOrganizationId(UUID organizationId, Pageable pageable);

  boolean existsByOrganizationIdAndCategoryIdAndPeriodAndCurrency(
      UUID organizationId, UUID categoryId, String period, String currency);

  boolean existsByOrganizationIdAndCategoryIdAndPeriodAndCurrencyAndIdNot(
      UUID organizationId, UUID categoryId, String period, String currency, UUID id);
}
