package com.ledgerly.api.alert;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns every read/dismiss mutation of {@link AlertState}. {@link Alert} itself is never touched
 * here — only the per-user state row alongside it. */
@Service
public class AlertStateService {

  private final AlertStateRepository alertStateRepository;
  private final AlertRepository alertRepository;

  public AlertStateService(AlertStateRepository alertStateRepository, AlertRepository alertRepository) {
    this.alertStateRepository = alertStateRepository;
    this.alertRepository = alertRepository;
  }

  public Map<UUID, AlertState> statesFor(UUID userId, List<UUID> alertIds) {
    if (alertIds.isEmpty()) {
      return Map.of();
    }
    return alertStateRepository.findByUserIdAndAlertIdIn(userId, alertIds).stream()
        .collect(
            java.util.stream.Collectors.toMap(
                AlertState::getAlertId, Function.identity()));
  }

  /** Idempotent — a second call for an already-read alert is a no-op. */
  @Transactional
  public void markRead(UUID alertId, UUID userId) {
    AlertState state = stateFor(alertId, userId);
    state.markRead();
    alertStateRepository.save(state);
  }

  @Transactional
  public void markAllRead(UUID organizationId, UUID userId) {
    List<Alert> alerts =
        alertRepository.findVisible(organizationId, userId, null, Pageable.unpaged());
    for (Alert alert : alerts) {
      AlertState state = stateFor(alert.getId(), userId);
      state.markRead();
      alertStateRepository.save(state);
    }
  }

  /** Idempotent — a second call for an already-dismissed alert is a no-op. */
  @Transactional
  public void markDismissed(UUID alertId, UUID userId) {
    AlertState state = stateFor(alertId, userId);
    state.markDismissed();
    alertStateRepository.save(state);
  }

  private AlertState stateFor(UUID alertId, UUID userId) {
    return alertStateRepository
        .findByAlertIdAndUserId(alertId, userId)
        .orElseGet(() -> new AlertState(alertId, userId));
  }
}
