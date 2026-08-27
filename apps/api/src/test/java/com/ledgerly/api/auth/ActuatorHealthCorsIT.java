package com.ledgerly.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.api.alert.AlertRepository;
import com.ledgerly.api.alert.AlertStateRepository;
import com.ledgerly.api.anomaly.AnomalyHistoryRepository;
import com.ledgerly.api.audit.AuditLogRepository;
import com.ledgerly.api.budget.BudgetRepository;
import com.ledgerly.api.budget.BudgetSpendRepository;
import com.ledgerly.api.budget.BudgetThresholdEvaluator;
import com.ledgerly.api.category.CategoryRepository;
import com.ledgerly.api.dashboard.DashboardRepository;
import com.ledgerly.api.document.DocumentActivityRepository;
import com.ledgerly.api.document.DocumentRepository;
import com.ledgerly.api.expense.ExpenseRepository;
import com.ledgerly.api.expense.ExpenseStubRepository;
import com.ledgerly.api.idempotency.IdempotencyRecordRepository;
import com.ledgerly.api.ledger.LedgerAccountRepository;
import com.ledgerly.api.ledger.LedgerTransactionRepository;
import com.ledgerly.api.policy.PolicyChunkRepository;
import com.ledgerly.api.policy.PolicyDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** {@code CorsConfig}'s CORS bean only covers {@code /api/**} — actuator has its own, separate
 * CORS mechanism ({@code management.endpoints.web.cors.*} in application.yml), which used to
 * carry a hardcoded {@code http://localhost:3000} regardless of {@code CORS_ALLOWED_ORIGINS}.
 * The browser-side /health page fetches this service's own /actuator/health directly, so every
 * deployed environment reported the service "Unreachable" despite it being genuinely up.
 *
 * <p>{@code webEnvironment = RANDOM_PORT} against a real embedded server, not {@code MockMvc}: a
 * mocked servlet context did not reproduce the bug (a first attempt at this test using MockMvc
 * passed even against the unfixed application.yml), so it cannot be trusted to catch a
 * regression here either. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
      "management.endpoint.health.show-details=never",
      "management.endpoints.web.cors.allowed-origins=https://ledgerly.vercel.app",
      "management.endpoints.web.cors.allowed-methods=GET"
    })
class ActuatorHealthCorsIT {

  @org.springframework.beans.factory.annotation.Autowired private TestRestTemplate restTemplate;

  @MockitoBean private com.ledgerly.api.auth.AppUserRepository appUserRepository;
  @MockitoBean private com.ledgerly.api.auth.OrganizationRepository organizationRepository;
  @MockitoBean private com.ledgerly.api.auth.RefreshTokenRepository refreshTokenRepository;
  @MockitoBean private ExpenseStubRepository expenseStubRepository;
  @MockitoBean private IdempotencyRecordRepository idempotencyRecordRepository;
  @MockitoBean private AuditLogRepository auditLogRepository;
  @MockitoBean private DocumentRepository documentRepository;
  @MockitoBean private DocumentActivityRepository documentActivityRepository;
  @MockitoBean private CategoryRepository categoryRepository;
  @MockitoBean private BudgetRepository budgetRepository;
  @MockitoBean private BudgetSpendRepository budgetSpendRepository;
  @MockitoBean private AnomalyHistoryRepository anomalyHistoryRepository;
  @MockitoBean private JdbcTemplate jdbcTemplate;
  @MockitoBean private BudgetThresholdEvaluator budgetThresholdEvaluator;
  @MockitoBean private AlertRepository alertRepository;
  @MockitoBean private AlertStateRepository alertStateRepository;
  @MockitoBean private PolicyDocumentRepository policyDocumentRepository;
  @MockitoBean private PolicyChunkRepository policyChunkRepository;
  @MockitoBean private ExpenseRepository expenseRepository;
  @MockitoBean private LedgerAccountRepository ledgerAccountRepository;
  @MockitoBean private LedgerTransactionRepository ledgerTransactionRepository;
  @MockitoBean private DashboardRepository dashboardRepository;
  @MockitoBean private RedisConnectionFactory redisConnectionFactory;
  @MockitoBean private StringRedisTemplate stringRedisTemplate;

  @Test
  void anOriginAllowedByCorsAllowedOriginsGetsAccessControlHeadersFromActuatorHealth() {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.ORIGIN, "https://ledgerly.vercel.app");

    ResponseEntity<String> response =
        restTemplate.exchange(
            "/actuator/health", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getHeaders().getAccessControlAllowOrigin())
        .isEqualTo("https://ledgerly.vercel.app");
  }

  @Test
  void anOriginNotInCorsAllowedOriginsIsRejectedByActuatorHealth() {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.ORIGIN, "https://evil.example.com");

    ResponseEntity<String> response =
        restTemplate.exchange(
            "/actuator/health", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(403);
  }
}
