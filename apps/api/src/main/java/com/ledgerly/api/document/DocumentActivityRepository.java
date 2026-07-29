package com.ledgerly.api.document;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentActivityRepository extends JpaRepository<DocumentActivity, Long> {

  List<DocumentActivity> findByDocumentIdAndOrganizationIdAndIdGreaterThanOrderByIdAsc(
      UUID documentId, UUID organizationId, long afterId);
}
