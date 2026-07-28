package com.ledgerly.api.document;

import java.util.UUID;

/**
 * Published by {@link DocumentStatusTransitions} after every status write, for {@link
 * DocumentEventPublisher} to relay to Redis once the transaction commits — never before, since a
 * subscriber reacting to a status that then rolls back would show a client a state the database
 * never actually reached.
 */
public record DocumentStatusChangedEvent(
    UUID documentId, UUID organizationId, DocumentStatus status, String detail) {}
