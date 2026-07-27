package com.ledgerly.api.policy;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, UUID> {

  /** Mirrors {@link com.ledgerly.api.document.DocumentRepository}'s org-scoped lookup pattern. */
  Optional<PolicyDocument> findByIdAndOrganizationId(UUID id, UUID organizationId);

  long countByOrganizationId(UUID organizationId);
}
