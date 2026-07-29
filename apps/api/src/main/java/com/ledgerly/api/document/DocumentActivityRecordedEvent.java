package com.ledgerly.api.document;

import java.util.UUID;

/** Published in-transaction and relayed after commit for a low-latency SSE update. */
public record DocumentActivityRecordedEvent(long activityId, UUID documentId, UUID organizationId) {}
