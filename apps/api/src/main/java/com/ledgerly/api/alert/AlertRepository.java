package com.ledgerly.api.alert;

import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

  boolean existsByBudgetIdAndThresholdPercent(UUID budgetId, int thresholdPercent);

  boolean existsByExpenseIdAndAlertType(UUID expenseId, String alertType);

  List<Alert> findByOrganizationId(UUID organizationId, Pageable pageable);

  long countByOrganizationId(UUID organizationId);

  /**
   * The caller's non-dismissed alerts, optionally narrowed to one {@code alertType}. {@code
   * :type IS NULL} makes the type filter optional in one query rather than branching between two
   * repository methods. A {@code LEFT JOIN} against {@code alert_state} is required (not a
   * subquery) because "no state row for this user" and "a state row with a null dismissedAt" must
   * both count as "not dismissed."
   */
  @Query(
      "SELECT a FROM Alert a LEFT JOIN AlertState s ON s.alertId = a.id AND s.userId = :userId "
          + "WHERE a.organizationId = :organizationId "
          + "AND (:type IS NULL OR a.alertType = :type) "
          + "AND (s IS NULL OR s.dismissedAt IS NULL) "
          + "ORDER BY a.createdAt DESC")
  List<Alert> findVisible(
      @Param("organizationId") UUID organizationId,
      @Param("userId") UUID userId,
      @Param("type") String type,
      Pageable pageable);

  /** Count of the caller's non-dismissed alerts with no read state yet — the sidebar badge. */
  @Query(
      "SELECT COUNT(a) FROM Alert a LEFT JOIN AlertState s ON s.alertId = a.id AND s.userId = :userId "
          + "WHERE a.organizationId = :organizationId "
          + "AND (s IS NULL OR (s.dismissedAt IS NULL AND s.readAt IS NULL))")
  long countUnread(@Param("organizationId") UUID organizationId, @Param("userId") UUID userId);
}
