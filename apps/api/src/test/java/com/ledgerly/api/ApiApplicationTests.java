package com.ledgerly.api;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerly.api.audit.AuditLogRepository;
import com.ledgerly.api.auth.AppUserRepository;
import com.ledgerly.api.auth.OrganizationRepository;
import com.ledgerly.api.auth.RefreshTokenRepository;
import com.ledgerly.api.category.CategoryRepository;
import com.ledgerly.api.dashboard.DashboardRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"
    })
@AutoConfigureMockMvc
class ApiApplicationTests {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AppUserRepository appUserRepository;
  @MockitoBean private OrganizationRepository organizationRepository;
  @MockitoBean private RefreshTokenRepository refreshTokenRepository;
  @MockitoBean private ExpenseStubRepository expenseStubRepository;
  @MockitoBean private IdempotencyRecordRepository idempotencyRecordRepository;
  @MockitoBean private AuditLogRepository auditLogRepository;
  @MockitoBean private DocumentRepository documentRepository;
  @MockitoBean private CategoryRepository categoryRepository;
  @MockitoBean private PolicyDocumentRepository policyDocumentRepository;
  @MockitoBean private PolicyChunkRepository policyChunkRepository;
  @MockitoBean private ExpenseRepository expenseRepository;
  @MockitoBean private LedgerAccountRepository ledgerAccountRepository;
  @MockitoBean private LedgerTransactionRepository ledgerTransactionRepository;
  @MockitoBean private DashboardRepository dashboardRepository;

  @Test
  void contextLoads() {}

  @Test
  void healthEndpointReportsUp() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("UP")));
  }

  @Test
  void envEndpointIsNotExposed() throws Exception {
    mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
  }

  @Test
  void beansEndpointIsNotExposed() throws Exception {
    mockMvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
  }

  @Test
  void configpropsEndpointIsNotExposed() throws Exception {
    mockMvc.perform(get("/actuator/configprops")).andExpect(status().isNotFound());
  }

  @Test
  void unauthenticatedRequestToUnknownRouteIsRejectedBeforeRouting() throws Exception {
    // Security fails closed: an unmapped route on a protected path is rejected at the filter
    // chain, before Spring MVC gets a chance to answer 404 — so the response never reveals which
    // routes exist. 401 rather than 403 because the caller presented no credentials at all.
    mockMvc.perform(get("/does-not-exist")).andExpect(status().isUnauthorized());
  }

  @Test
  void unmappedActuatorSubPathReturnsProblemDetailJsonWithNoStackTrace() throws Exception {
    mockMvc
        .perform(get("/actuator/env"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status", is(404)))
        .andExpect(jsonPath("$.detail").exists())
        .andExpect(jsonPath("$.trace").doesNotExist());
  }
}
