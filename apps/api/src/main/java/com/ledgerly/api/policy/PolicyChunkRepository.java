package com.ledgerly.api.policy;

import com.pgvector.PGvector;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Plain-JDBC persistence for {@code policy_chunk}, mirroring how {@code ledger_entry} is written
 * in this codebase — Hibernate has no first-class {@code vector} column mapping, so pgvector's own
 * {@link PGvector} JDBC binding is used directly rather than pulling in a Hibernate vector-type
 * dependency for one column.
 */
@Repository
public class PolicyChunkRepository {

  private final JdbcTemplate jdbcTemplate;

  public PolicyChunkRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void saveAll(List<PolicyChunk> chunks) {
    jdbcTemplate.batchUpdate(
        "INSERT INTO policy_chunk "
            + "(organization_id, policy_document_id, chunk_index, chunk_text, embedding) "
            + "VALUES (?, ?, ?, ?, ?)",
        chunks,
        chunks.size(),
        (PreparedStatement ps, PolicyChunk chunk) -> {
          ps.setObject(1, chunk.organizationId());
          ps.setObject(2, chunk.policyDocumentId());
          ps.setInt(3, chunk.chunkIndex());
          ps.setString(4, chunk.chunkText());
          ps.setObject(5, new PGvector(chunk.embedding()));
        });
  }

  public long countByPolicyDocumentId(UUID policyDocumentId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM policy_chunk WHERE policy_document_id = ?",
            Long.class,
            policyDocumentId);
    return count == null ? 0 : count;
  }

  public long countByOrganizationId(UUID organizationId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM policy_chunk WHERE organization_id = ?",
            Long.class,
            organizationId);
    return count == null ? 0 : count;
  }

  /**
   * Chunk counts for every given document in one query, so listing N policy documents never
   * issues N count queries. A document with zero chunks is absent from the map rather than
   * present with a zero entry; callers default missing keys to zero.
   */
  public Map<UUID, Long> countByPolicyDocumentIds(UUID organizationId, List<UUID> policyDocumentIds) {
    if (policyDocumentIds.isEmpty()) {
      return Map.of();
    }
    List<Map.Entry<UUID, Long>> rows =
        jdbcTemplate.query(
            (java.sql.Connection connection) -> {
              PreparedStatement ps =
                  connection.prepareStatement(
                      "SELECT policy_document_id, COUNT(*) AS chunk_count FROM policy_chunk "
                          + "WHERE organization_id = ? AND policy_document_id = ANY (?) "
                          + "GROUP BY policy_document_id");
              ps.setObject(1, organizationId);
              ps.setArray(2, connection.createArrayOf("uuid", policyDocumentIds.toArray()));
              return ps;
            },
            (RowMapper<Map.Entry<UUID, Long>>)
                (rs, rowNum) ->
                    Map.entry(
                        (UUID) rs.getObject("policy_document_id"), rs.getLong("chunk_count")));
    return rows.stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /**
   * A document's chunks in index order, org-scoped, with the embedding excluded at the SQL
   * projection — a vector reaching a client-facing response is a data-exposure defect, not a
   * formatting one, so it must never be selected here in the first place.
   */
  public List<PolicyChunk> findByPolicyDocumentIdOrderByChunkIndex(
      UUID organizationId, UUID policyDocumentId, int offset, int limit) {
    return jdbcTemplate.query(
        "SELECT organization_id, policy_document_id, chunk_index, chunk_text "
            + "FROM policy_chunk WHERE organization_id = ? AND policy_document_id = ? "
            + "ORDER BY chunk_index ASC LIMIT ? OFFSET ?",
        (rs, rowNum) ->
            new PolicyChunk(
                (UUID) rs.getObject("organization_id"),
                (UUID) rs.getObject("policy_document_id"),
                rs.getInt("chunk_index"),
                rs.getString("chunk_text"),
                null),
        organizationId,
        policyDocumentId,
        limit,
        offset);
  }

  /**
   * Nearest neighbours by cosine distance, scoped to one organization. Used by M6 T4's
   * categorization retrieval step.
   */
  public List<PolicyChunk> findNearest(UUID organizationId, float[] queryEmbedding, int limit) {
    return jdbcTemplate.query(
        "SELECT organization_id, policy_document_id, chunk_index, chunk_text, embedding "
            + "FROM policy_chunk WHERE organization_id = ? "
            + "ORDER BY embedding <=> ? LIMIT ?",
        rowMapper(),
        organizationId,
        new PGvector(queryEmbedding),
        limit);
  }

  private RowMapper<PolicyChunk> rowMapper() {
    return (rs, rowNum) -> {
      try {
        PGvector vector = new PGvector(rs.getString("embedding"));
        return new PolicyChunk(
            (UUID) rs.getObject("organization_id"),
            (UUID) rs.getObject("policy_document_id"),
            rs.getInt("chunk_index"),
            rs.getString("chunk_text"),
            vector.toArray());
      } catch (SQLException e) {
        throw new IllegalStateException("Failed to read policy_chunk row", e);
      }
    };
  }
}
