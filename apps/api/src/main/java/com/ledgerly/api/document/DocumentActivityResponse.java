package com.ledgerly.api.document;

import java.time.Instant;

/** The persisted activity contract used by expense detail and SSE replay. */
public record DocumentActivityResponse(
    long id, DocumentActivityStage stage, String detail, Instant createdAt) {

  static DocumentActivityResponse from(DocumentActivity activity) {
    return new DocumentActivityResponse(
        activity.getId(), activity.getStage(), activity.getDetail(), activity.getCreatedAt());
  }
}
