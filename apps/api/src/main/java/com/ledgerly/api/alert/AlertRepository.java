package com.ledgerly.api.alert;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

  boolean existsByBudgetIdAndThresholdPercent(UUID budgetId, int thresholdPercent);
}
