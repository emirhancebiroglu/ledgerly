package com.ledgerly.api.policy;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * What a client is allowed to see about a policy document. Deliberately omits {@code storageKey}
 * — mirrors {@link com.ledgerly.api.document.DocumentResponse}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyDocumentResponse(
    UUID id,
    String filename,
    PolicyDocumentStatus status,
    String failureReason,
    Instant createdAt) {

  public static PolicyDocumentResponse from(PolicyDocument document) {
    return new PolicyDocumentResponse(
        document.getId(),
        document.getFilename(),
        document.getStatus(),
        document.getFailureReason(),
        document.getCreatedAt());
  }
}
