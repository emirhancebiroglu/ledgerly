package com.ledgerly.api.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import java.util.UUID;

/**
 * What a client is allowed to see about a document.
 *
 * <p>Deliberately omits {@code storageKey}: it is an internal handle, and echoing it back would
 * hand a caller a value that only the server should ever hold. Nothing here is a filesystem path.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentResponse(
    UUID id,
    String filename,
    String contentType,
    long sizeBytes,
    DocumentStatus status,
    @JsonRawValue String proposal,
    String failureReason,
    Instant createdAt) {

  public static DocumentResponse from(Document document) {
    return new DocumentResponse(
        document.getId(),
        document.getFilename(),
        document.getContentType(),
        document.getSizeBytes(),
        document.getStatus(),
        document.getProposal(),
        document.getFailureReason(),
        document.getCreatedAt());
  }
}
