package com.ledgerly.api.document;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

  /**
   * The reaper's candidate set. Deliberately not org-scoped, unlike every other query here — a
   * stuck document is a global operational concern, not a tenant one. Paged deliberately, like
   * {@link #findByOrganizationIdOrderByCreatedAtDesc}: an outage can strand an unbounded number of
   * documents in {@code PROCESSING}, and one sweep loading every one of them into a {@code List}
   * would turn a provider outage into a memory spike here too. The fixed-delay schedule drains any
   * backlog across cycles.
   */
  List<Document> findByStatusAndUpdatedAtBefore(
      DocumentStatus status, Instant cutoff, Pageable pageable);

  /**
   * Atomically reclaims one stuck document: the {@code WHERE status = :expectedStatus} makes this
   * safe with two reaper instances racing the same row — whichever one's UPDATE runs first flips
   * the status, and the loser's statement matches zero rows and is a no-op, not a second write.
   */
  @Modifying
  @Query(
      "UPDATE Document d SET d.status = com.ledgerly.api.document.DocumentStatus.FAILED, "
          + "d.failureReason = :reason, d.updatedAt = :now "
          + "WHERE d.id = :id AND d.status = :expectedStatus AND d.updatedAt < :cutoff")
  int reclaimStuckDocument(
      @Param("id") UUID id,
      @Param("expectedStatus") DocumentStatus expectedStatus,
      @Param("cutoff") Instant cutoff,
      @Param("now") Instant now,
      @Param("reason") String reason);
}
