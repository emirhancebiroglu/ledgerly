package com.ledgerly.api.alert;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key for {@link AlertState}: one row per (alert, viewing user). */
public class AlertStateId implements Serializable {

  private UUID alertId;
  private UUID userId;

  protected AlertStateId() {}

  public AlertStateId(UUID alertId, UUID userId) {
    this.alertId = alertId;
    this.userId = userId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof AlertStateId that)) return false;
    return Objects.equals(alertId, that.alertId) && Objects.equals(userId, that.userId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(alertId, userId);
  }
}
