package com.ledgerly.api.document;

import java.util.UUID;

/** The Redis channel-naming convention shared by {@link DocumentEventPublisher} (publisher) and
 * {@code DocumentEventController} (subscriber) — one channel per document, so a subscriber only
 * ever receives events for the document it asked about. */
final class DocumentEventChannels {

  private DocumentEventChannels() {}

  static String statusChannelFor(UUID documentId) {
    return "document-events:" + documentId;
  }

  static String activityChannelFor(UUID documentId) {
    return "document-activities:" + documentId;
  }
}
