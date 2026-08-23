package com.ledgerly.api.policy;

/**
 * A single indexed passage. Deliberately excludes the embedding vector — {@link PolicyChunk}
 * carries it for retrieval, but nothing client-facing may return it.
 */
public record PolicyChunkResponse(int index, String text) {

  public static PolicyChunkResponse from(PolicyChunk chunk) {
    return new PolicyChunkResponse(chunk.chunkIndex(), chunk.chunkText());
  }
}
