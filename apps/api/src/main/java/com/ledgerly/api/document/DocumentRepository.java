package com.ledgerly.api.document;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

  /**
   * Serializes activity appends for one document. The SSE cursor is the activity id, so commits
   * for the same stream must not become visible out of id order.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT d FROM Document d WHERE d.id = :id AND d.organizationId = :organizationId")
  Optional<Document> lockByIdAndOrganizationId(
      @Param("id") UUID id, @Param("organizationId") UUID organizationId);

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

  /** Bounded candidate scan for the durable extraction queue; the following claim is atomic. */
  List<Document> findByStatusAndNextAttemptAtLessThanEqual(
      DocumentStatus status, Instant now, Pageable pageable);

  /**
   * Atomically claims a due queue row. Candidate selection is deliberately separate from this
   * write: two instances can select the same id, but only one can change its current PENDING row
   * to PROCESSING and increment its attempt count.
   */
  @Modifying
  @Query(
      "UPDATE Document d SET d.status = com.ledgerly.api.document.DocumentStatus.PROCESSING, "
          + "d.extractionAttempts = d.extractionAttempts + 1, d.failureReason = null, "
          + "d.updatedAt = :now WHERE d.id = :id "
          + "AND d.status = com.ledgerly.api.document.DocumentStatus.PENDING "
          + "AND d.nextAttemptAt <= :now AND d.extractionAttempts < :maxAttempts")
  int claimDueDocument(
      @Param("id") UUID id, @Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

  /** Releases a claim that could not enter the worker executor without spending a retry attempt. */
  @Modifying
  @Query(
      "UPDATE Document d SET d.status = com.ledgerly.api.document.DocumentStatus.PENDING, "
          + "d.extractionAttempts = d.extractionAttempts - 1, d.nextAttemptAt = :retryAt, "
          + "d.failureReason = :reason, d.updatedAt = :now WHERE d.id = :id "
          + "AND d.status = com.ledgerly.api.document.DocumentStatus.PROCESSING "
          + "AND d.extractionAttempts > 0")
  int releaseClaimAfterDispatchRejection(
      @Param("id") UUID id,
      @Param("now") Instant now,
      @Param("retryAt") Instant retryAt,
      @Param("reason") String reason);

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
