package com.ledgerly.api.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

  /**
   * The only lookup by id callers should use. Taking the organization as part of the query — rather
   * than loading by id and checking the org afterwards — means a document belonging to another
   * tenant is indistinguishable from one that does not exist.
   */
  Optional<Document> findByIdAndOrganizationId(UUID id, UUID organizationId);

  /** Paged deliberately: an organization's document list grows without bound. */
  List<Document> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

  long countByOrganizationId(UUID organizationId);
}
