package com.ledgerly.api.category;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasItem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthResponse;
import com.ledgerly.api.auth.RegisterRequest;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** {@code /api/v1/categories} CRUD: per-org uniqueness, cross-org isolation. */
@AutoConfigureMockMvc
class CategoryIT extends AbstractPostgresIT {

  private static final String TEST_JWT_SECRET = "test-only-secret-not-for-production-use-0123456789";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void registrationProvisionsTheStarterCategoryTaxonomy() throws Exception {
    String token = registerAndGetAccessToken();

    mockMvc
        .perform(get("/api/v1/categories").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(12))
        .andExpect(jsonPath("$[*].name", hasItem("Travel & Transport")));
  }

  @Test
  void aCategoryCanBeCreatedListedAndFetched() throws Exception {
    String token = registerAndGetAccessToken();

    MvcResult created =
        create(token, "Travel").andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Travel"))
            .andReturn();
    String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

    mockMvc
        .perform(get("/api/v1/categories").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].name", hasItem("Travel")));

    mockMvc
        .perform(get("/api/v1/categories/" + id).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Travel"));
  }

  @Test
  void aCategoryCanBeRenamedAndDeleted() throws Exception {
    String token = registerAndGetAccessToken();
    String id = createAndGetId(token, "Office Supplies");

    mockMvc
        .perform(
            put("/api/v1/categories/" + id)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "key-" + System.nanoTime())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CategoryRequest("Supplies"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Supplies"));

    mockMvc
        .perform(
            delete("/api/v1/categories/" + id)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "key-" + System.nanoTime()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/v1/categories/" + id).header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }

  @Test
  void duplicateNameInTheSameOrganizationIsRejectedWith409() throws Exception {
    String token = registerAndGetAccessToken();
    create(token, "Travel").andExpect(status().isCreated());

    create(token, "Travel").andExpect(status().isConflict());
  }

  @Test
  void theSameNameInADifferentOrganizationIsAllowed() throws Exception {
    String tokenA = registerAndGetAccessToken();
    String tokenB = registerAndGetAccessToken();

    create(tokenA, "Travel").andExpect(status().isCreated());
    create(tokenB, "Travel").andExpect(status().isCreated());
  }

  @Test
  void renamingToAnExistingNameInTheSameOrganizationIsRejectedWith409() throws Exception {
    String token = registerAndGetAccessToken();
    createAndGetId(token, "Travel");
    String officeId = createAndGetId(token, "Office");

    mockMvc
        .perform(
            put("/api/v1/categories/" + officeId)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "key-" + System.nanoTime())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CategoryRequest("Travel"))))
        .andExpect(status().isConflict());
  }

  @Test
  void anotherOrganizationsCategoryIsNotReadableOrWritable() throws Exception {
    String tokenA = registerAndGetAccessToken();
    String tokenB = registerAndGetAccessToken();
    String id = createAndGetId(tokenA, "Travel");

    mockMvc
        .perform(get("/api/v1/categories/" + id).header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(
            put("/api/v1/categories/" + id)
                .header("Authorization", "Bearer " + tokenB)
                .header("Idempotency-Key", "key-" + System.nanoTime())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CategoryRequest("Hacked"))))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(
            delete("/api/v1/categories/" + id)
                .header("Authorization", "Bearer " + tokenB)
                .header("Idempotency-Key", "key-" + System.nanoTime()))
        .andExpect(status().isNotFound());
  }

  @Test
  void unauthenticatedReturns401() throws Exception {
    mockMvc.perform(get("/api/v1/categories")).andExpect(status().isUnauthorized());
  }

  @Test
  void deletingACategoryStillReferencedByAnExpenseReturns409() throws Exception {
    String token = registerAndGetAccessToken();
    UUID orgId = organizationIdOf(token);
    String categoryId = createAndGetId(token, "Travel");
    UUID userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM app_user WHERE organization_id = ?", UUID.class, orgId);
    UUID documentId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO document (id, organization_id, uploaded_by, filename, content_type, "
            + "size_bytes, storage_key, content_hash, status) "
            + "VALUES (?, ?, ?, 'invoice.pdf', 'application/pdf', 100, ?, 'hash', 'EXTRACTED')",
        documentId,
        orgId,
        userId,
        UUID.randomUUID().toString());
    jdbcTemplate.update(
        "INSERT INTO expense (organization_id, document_id, category_id, amount_minor, currency, "
            + "categorization_confidence, status) "
            + "VALUES (?, ?, ?::uuid, 1000, 'EUR', 0.9, 'NEEDS_REVIEW')",
        orgId,
        documentId,
        categoryId);

    mockMvc
        .perform(
            delete("/api/v1/categories/" + categoryId)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "key-" + System.nanoTime()))
        .andExpect(status().isConflict());
  }

  private org.springframework.test.web.servlet.ResultActions create(String token, String name)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/categories")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "key-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new CategoryRequest(name))));
  }

  private String createAndGetId(String token, String name) throws Exception {
    MvcResult result = create(token, name).andExpect(status().isCreated()).andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private String registerAndGetAccessToken() throws Exception {
    String email = "category-user-" + System.nanoTime() + "@example.com";
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new RegisterRequest(
                                "org-" + System.nanoTime(), email, "correct-horse-battery"))))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper
        .readValue(result.getResponse().getContentAsString(), AuthResponse.class)
        .accessToken();
  }

  private UUID organizationIdOf(String accessToken) {
    SecretKey key = Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));
    var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(accessToken).getPayload();
    return UUID.fromString(claims.get("org", String.class));
  }
}
