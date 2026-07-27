package com.ledgerly.api.correlation;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Derives the UUID an audit row's {@code correlation_id} column stores from whatever correlation
 * id (if any) is current on this request thread.
 *
 * <p>A UUID-shaped header round-trips unchanged. A non-UUID header (or none at all) is derived
 * deterministically via {@link UUID#nameUUIDFromBytes}, so the same input always produces the
 * same audit-row value — unlike {@link UUID#randomUUID()}, which would make two audit rows for
 * the same logical request uncorrelated with each other.
 */
public final class CorrelationIds {

  /** Used when no correlation id is present on the thread at all. */
  private static final String NONE_MARKER = "ledgerly:no-correlation-id";

  private CorrelationIds() {}

  public static UUID current() {
    String value = CorrelationIdHolder.current();
    return of(value);
  }

  static UUID of(String value) {
    String toDerive = value == null ? NONE_MARKER : value;
    try {
      return UUID.fromString(toDerive);
    } catch (IllegalArgumentException notAUuid) {
      // A client-supplied X-Correlation-Id need not be a UUID; audit_log.correlation_id is UUID
      // NOT NULL, so derive a stable one deterministically rather than losing the correlation
      // entirely. Trade-off: for a non-UUID header, the audit row's correlation_id will NOT
      // match the literal value in logs or the X-Correlation-Id response header — tracing a
      // support ticket by that literal value back to an audit row needs this same derivation,
      // not a straight string match.
      return UUID.nameUUIDFromBytes(toDerive.getBytes(StandardCharsets.UTF_8));
    }
  }
}
