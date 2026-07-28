package com.ledgerly.api.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthResponse;
import com.ledgerly.api.auth.RegisterRequest;
import com.ledgerly.api.category.CategoryRequest;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** {@code /api/v1/budgets} CRUD: exact values, idempotency, validation and tenant isolation. */
@AutoConfigureMockMvc
class BudgetIT extends AbstractPostgresIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void aBudgetCanBeCreatedListedFetchedUpdatedAndDeletedWithAuditRows() throws Exception {
    String token = registerAndGetAccessToken();
    String categoryId = createCategory(token, "SaaS");
    BudgetRequest create = new BudgetRequest(UUID.fromString(categoryId), "2026-08", 500_000, "EUR");

    MvcResult created = createBudget(token, "create-" + System.nanoTime(), create)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.categoryId").value(categoryId))
        .andExpect(jsonPath("$.period").value("2026-08"))
        .andExpect(jsonPath("$.limitMinor").value(500_000))
        .andExpect(jsonPath("$.currency").value("EUR"))
        .andReturn();
    String budgetId = jsonId(created);

    mockMvc
        .perform(get("/api/v1/budgets?page=0&size=1").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(budgetId));
    mockMvc
        .perform(get("/api/v1/budgets/" + budgetId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limitMinor").value(500_000));

    BudgetRequest update = new BudgetRequest(UUID.fromString(categoryId), "2026-09", 600_000, "EUR");
    mockMvc
        .perform(
            put("/api/v1/budgets/" + budgetId)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "update-" + System.nanoTime())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.period").value("2026-09"))
        .andExpect(jsonPath("$.limitMinor").value(600_000));

    mockMvc
        .perform(
            delete("/api/v1/budgets/" + budgetId)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "delete-" + System.nanoTime()))
        .andExpect(status().isNoContent());

    assertThat(auditActions(budgetId)).containsExactlyInAnyOrder("CREATE", "UPDATE", "DELETE");
  }

  @Test
  void duplicateDefinitionIsConflictAndIdempotencyCreatesOneEffect() throws Exception {
    String token = registerAndGetAccessToken();
    String categoryId = createCategory(token, "SaaS");
    BudgetRequest request = new BudgetRequest(UUID.fromString(categoryId), "2026-08", 500_000, "EUR");
    String key = "replay-" + System.nanoTime();

    MvcResult first = createBudget(token, key, request).andExpect(status().isCreated()).andReturn();
    MvcResult replay = createBudget(token, key, request).andExpect(status().isCreated()).andReturn();
    assertThat(replay.getResponse().getContentAsString())
        .isEqualTo(first.getResponse().getContentAsString());
    assertThat(countBudgets(UUID.fromString(categoryId))).isEqualTo(1);

    createBudget(token, key, new BudgetRequest(UUID.fromString(categoryId), "2026-08", 600_000, "EUR"))
        .andExpect(status().isConflict());
    createBudget(token, "duplicate-" + System.nanoTime(), request).andExpect(status().isConflict());
  }

  @Test
  void anotherOrganizationsBudgetAndCategoryAreNotAccessible() throws Exception {
    String tokenA = registerAndGetAccessToken();
    String tokenB = registerAndGetAccessToken();
    String categoryA = createCategory(tokenA, "SaaS");
    String budgetA =
        jsonId(
            createBudget(
                    tokenA,
                    "create-a-" + System.nanoTime(),
                    new BudgetRequest(UUID.fromString(categoryA), "2026-08", 500_000, "EUR"))
                .andExpect(status().isCreated())
                .andReturn());

    mockMvc
        .perform(get("/api/v1/budgets").header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    mockMvc
        .perform(get("/api/v1/budgets/" + budgetA).header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            put("/api/v1/budgets/" + budgetA)
                .header("Authorization", "Bearer " + tokenB)
                .header("Idempotency-Key", "other-update-" + System.nanoTime())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new BudgetRequest(UUID.fromString(categoryA), "2026-09", 1, "EUR"))))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            delete("/api/v1/budgets/" + budgetA)
                .header("Authorization", "Bearer " + tokenB)
                .header("Idempotency-Key", "other-delete-" + System.nanoTime()))
        .andExpect(status().isNotFound());
    createBudget(
            tokenB,
            "other-category-" + System.nanoTime(),
            new BudgetRequest(UUID.fromString(categoryA), "2026-08", 1, "EUR"))
        .andExpect(status().isNotFound());
  }

  @Test
  void malformedValuesAndUnknownCurrencyAreBadRequest() throws Exception {
    String token = registerAndGetAccessToken();
    String categoryId = createCategory(token, "SaaS");

    createBudget(token, "bad-period-" + System.nanoTime(), new BudgetRequest(UUID.fromString(categoryId), "2026-13", 1, "EUR"))
        .andExpect(status().isBadRequest());
    createBudget(token, "bad-limit-" + System.nanoTime(), new BudgetRequest(UUID.fromString(categoryId), "2026-08", 0, "EUR"))
        .andExpect(status().isBadRequest());
    createBudget(token, "bad-currency-" + System.nanoTime(), new BudgetRequest(UUID.fromString(categoryId), "2026-08", 1, "ZZZ"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/budgets?page=-1").header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/budgets?size=0").header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void concurrentCreatesWithDifferentKeysReturnConflictInsteadOf500() throws Exception {
    String token = registerAndGetAccessToken();
    String categoryId = createCategory(token, "SaaS");
    BudgetRequest request = new BudgetRequest(UUID.fromString(categoryId), "2026-08", 500_000, "EUR");

    List<MvcResult> results =
        race(
            () -> createBudget(token, "race-create-a-" + System.nanoTime(), request).andReturn(),
            () -> createBudget(token, "race-create-b-" + System.nanoTime(), request).andReturn());

    assertThat(results.stream().map(result -> result.getResponse().getStatus()).toList())
        .containsExactlyInAnyOrder(201, 409);
    assertThat(countBudgets(UUID.fromString(categoryId))).isEqualTo(1);
  }

  @Test
  void concurrentUpdatesWithDifferentKeysReturnConflictInsteadOf500() throws Exception {
    String token = registerAndGetAccessToken();
    String firstCategory = createCategory(token, "First");
    String secondCategory = createCategory(token, "Second");
    String sharedCategory = createCategory(token, "Shared");
    String firstBudget =
        jsonId(
            createBudget(
                    token,
                    "first-budget-" + System.nanoTime(),
                    new BudgetRequest(UUID.fromString(firstCategory), "2026-08", 100, "EUR"))
                .andExpect(status().isCreated())
                .andReturn());
    String secondBudget =
        jsonId(
            createBudget(
                    token,
                    "second-budget-" + System.nanoTime(),
                    new BudgetRequest(UUID.fromString(secondCategory), "2026-08", 100, "EUR"))
                .andExpect(status().isCreated())
                .andReturn());
    BudgetRequest shared = new BudgetRequest(UUID.fromString(sharedCategory), "2026-08", 100, "EUR");

    List<MvcResult> results =
        race(
            () -> updateBudget(token, firstBudget, "race-update-a-" + System.nanoTime(), shared).andReturn(),
            () -> updateBudget(token, secondBudget, "race-update-b-" + System.nanoTime(), shared).andReturn());

    assertThat(results.stream().map(result -> result.getResponse().getStatus()).toList())
        .containsExactlyInAnyOrder(200, 409);
    assertThat(countBudgets(UUID.fromString(sharedCategory))).isEqualTo(1);
  }

  @Test
  void unauthenticatedBudgetReadReturns401() throws Exception {
    mockMvc.perform(get("/api/v1/budgets")).andExpect(status().isUnauthorized());
  }

  private ResultActions createBudget(String token, String key, BudgetRequest request) throws Exception {
    return mockMvc.perform(
        post("/api/v1/budgets")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)));
  }

  private ResultActions updateBudget(String token, String budgetId, String key, BudgetRequest request)
      throws Exception {
    return mockMvc.perform(
        put("/api/v1/budgets/" + budgetId)
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)));
  }

  private String createCategory(String token, String name) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/categories")
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", "category-" + System.nanoTime())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new CategoryRequest(name))))
            .andExpect(status().isCreated())
            .andReturn();
    return jsonId(result);
  }

  private String registerAndGetAccessToken() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new RegisterRequest(
                                "org-" + System.nanoTime(),
                                "budget-user-" + System.nanoTime() + "@example.com",
                                "correct-horse-battery"))))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper
        .readValue(result.getResponse().getContentAsString(), AuthResponse.class)
        .accessToken();
  }

  private String jsonId(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private int countBudgets(UUID categoryId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM budget WHERE category_id = ?", Integer.class, categoryId);
  }

  private java.util.List<String> auditActions(String budgetId) {
    return jdbcTemplate.queryForList(
        "SELECT action FROM audit_log WHERE entity_type = 'budget' AND entity_id = ?", String.class, UUID.fromString(budgetId));
  }

  private List<MvcResult> race(Callable<MvcResult> first, Callable<MvcResult> second)
      throws Exception {
    CountDownLatch bothReady = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<MvcResult> firstResult =
          executor.submit(
              () -> {
                bothReady.countDown();
                go.await(10, TimeUnit.SECONDS);
                return first.call();
              });
      Future<MvcResult> secondResult =
          executor.submit(
              () -> {
                bothReady.countDown();
                go.await(10, TimeUnit.SECONDS);
                return second.call();
              });
      assertThat(bothReady.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      return List.of(firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS));
    } finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
}
