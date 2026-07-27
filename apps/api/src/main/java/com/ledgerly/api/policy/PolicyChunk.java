package com.ledgerly.api.policy;

import java.util.UUID;

/**
 * A chunk of policy text and its embedding vector.
 *
 * <p>Not a JPA entity — like {@link com.ledgerly.api.ledger.LedgerTransaction}, persistence goes
 * through plain JDBC (see {@link PolicyChunkRepository}) because Hibernate has no first-class
 * mapping for pgvector's {@code vector} column type.
 */
public record PolicyChunk(
    UUID organizationId, UUID policyDocumentId, int chunkIndex, String chunkText, float[] embedding) {}
