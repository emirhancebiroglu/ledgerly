package com.ledgerly.api.alert;

import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

  boolean existsByBudgetIdAndThresholdPercent(UUID budgetId, int thresholdPercent);

  boolean existsByExpenseIdAndAlertType(UUID expenseId, String alertType);

  List<Alert> findByOrganizationId(UUID organizationId, Pageable pageable);

  long countByOrganizationId(UUID organizationId);
}
