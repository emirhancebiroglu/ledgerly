package com.ledgerly.api.idempotency;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class IdempotencyService {

  private final IdempotencyRecordTransactions transactions;

  public IdempotencyService(IdempotencyRecordTransactions transactions) {
    this.transactions = transactions;
  }

  public sealed interface ClaimResult permits Claimed, Replay {}

  public record Claimed(UUID recordId) implements ClaimResult {}

  public record Replay(int status, String body) implements ClaimResult {}

  /**
   * Claims the (org, key, endpoint) tuple for a new request, or returns the previously-stored
   * response to replay. Each attempt runs in its own transaction: if two callers race to insert
   * the same key, the loser's transaction rolls back cleanly on the unique-constraint violation,
   * and only then — in a fresh transaction, never the poisoned one — do we re-read the winner's
   * row and resolve against it.
   */
  public ClaimResult claimOrReplay(
      UUID organizationId, String key, String endpoint, String requestHash) {
    Optional<IdempotencyRecord> existing =
        transactions.findUsableRecord(organizationId, key, endpoint);
    if (existing.isPresent()) {
      return resolveExisting(existing.get(), requestHash);
    }

    try {
      UUID recordId = transactions.attemptClaim(organizationId, key, endpoint, requestHash);
      return new Claimed(recordId);
    } catch (DataAccessException raceLostToConcurrentClaim) {
      IdempotencyRecord record =
          transactions
              .findExisting(organizationId, key, endpoint)
              .orElseThrow(() -> raceLostToConcurrentClaim);
      return resolveExisting(record, requestHash);
    }
  }

  private ClaimResult resolveExisting(IdempotencyRecord record, String requestHash) {
    if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
      throw new IdempotencyConflictException("A request with this Idempotency-Key is still in progress");
    }
    if (!record.getRequestHash().equals(requestHash)) {
      throw new IdempotencyConflictException(
          "Idempotency-Key reused with a different request payload");
    }
    return new Replay(record.getResponseStatus(), record.getResponse());
  }

  public void complete(UUID recordId, int responseStatus, String responseBody) {
    transactions.complete(recordId, responseStatus, responseBody);
  }
}
