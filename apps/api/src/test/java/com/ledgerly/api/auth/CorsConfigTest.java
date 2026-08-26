package com.ledgerly.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
      "ledgerly.cors.allowed-origins=https://ledgerly.vercel.app, https://staging.ledgerly.vercel.app"
    })
@AutoConfigureMockMvc
class CorsConfigTest {

  @Autowired private MockMvc mockMvc;

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
  void preflightFromAnAllowedOriginIsGrantedAccessControlHeaders() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/auth/login")
                .header(HttpHeaders.ORIGIN, "https://ledgerly.vercel.app")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
        .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200))
        .andExpect(
            result ->
                assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .isEqualTo("https://ledgerly.vercel.app"));
  }

  @Test
  void preflightFromASecondConfiguredOriginIsAlsoGranted() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/auth/login")
                .header(HttpHeaders.ORIGIN, "https://staging.ledgerly.vercel.app")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
        .andExpect(
            result ->
                assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .isEqualTo("https://staging.ledgerly.vercel.app"));
  }

  @Test
  void preflightFromAnOriginNotInTheConfiguredListGetsNoAccessControlHeader() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/auth/login")
                .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
        .andExpect(
            result ->
                assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .isNull());
  }
}
