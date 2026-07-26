package com.ledgerly.api;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerly.api.auth.AppUserRepository;
import com.ledgerly.api.auth.OrganizationRepository;
import com.ledgerly.api.auth.RefreshTokenRepository;
import com.ledgerly.api.expense.ExpenseStubRepository;
import com.ledgerly.api.idempotency.IdempotencyRecordRepository;
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
    // Security fails closed: an unmapped route on a protected path is rejected at the
    // filter chain, before Spring MVC gets a chance to report 404 vs 403.
    mockMvc.perform(get("/does-not-exist")).andExpect(status().isForbidden());
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
