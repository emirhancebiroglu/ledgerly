package com.ledgerly.api.idempotency;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

  Optional<IdempotencyRecord> findByOrganizationIdAndKeyAndEndpoint(
      UUID organizationId, String key, String endpoint);

  void deleteByOrganizationIdAndKeyAndEndpoint(UUID organizationId, String key, String endpoint);
}
