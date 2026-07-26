package com.ledgerly.api.idempotency;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each method here runs in its own fresh transaction (REQUIRES_NEW), split into a separate bean
 * so {@link IdempotencyService} can call them without the self-invocation trap: a same-class
 * call bypasses Spring's transactional proxy and would silently run with no new transaction.
 */
@Component
class IdempotencyRecordTransactions {

  private static final long RECORD_TTL_HOURS = 24;

  private final IdempotencyRecordRepository repository;

  IdempotencyRecordTransactions(IdempotencyRecordRepository repository) {
    this.repository = repository;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  Optional<IdempotencyRecord> findUsableRecord(UUID organizationId, String key, String endpoint) {
    Optional<IdempotencyRecord> existing =
        repository.findByOrganizationIdAndKeyAndEndpoint(organizationId, key, endpoint);
    if (existing.isPresent() && existing.get().isExpired(Instant.now())) {
      repository.deleteByOrganizationIdAndKeyAndEndpoint(organizationId, key, endpoint);
      repository.flush();
      return Optional.empty();
    }
    return existing;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  UUID attemptClaim(UUID organizationId, String key, String endpoint, String requestHash) {
    IdempotencyRecord record =
        repository.save(
            new IdempotencyRecord(
                organizationId,
                key,
                endpoint,
                requestHash,
                Instant.now().plus(RECORD_TTL_HOURS, ChronoUnit.HOURS)));
    repository.flush();
    return record.getId();
  }

  Optional<IdempotencyRecord> findExisting(UUID organizationId, String key, String endpoint) {
    return repository.findByOrganizationIdAndKeyAndEndpoint(organizationId, key, endpoint);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void complete(UUID recordId, int responseStatus, String responseBody) {
    IdempotencyRecord record =
        repository.findById(recordId).orElseThrow(IllegalStateException::new);
    record.complete(responseStatus, responseBody);
    repository.save(record);
  }
}
