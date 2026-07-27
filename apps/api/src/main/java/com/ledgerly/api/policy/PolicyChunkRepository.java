package com.ledgerly.api.policy;

import com.pgvector.PGvector;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
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
