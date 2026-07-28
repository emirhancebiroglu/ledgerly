package com.ledgerly.api.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthResponse;
import com.ledgerly.api.auth.RegisterRequest;
import com.ledgerly.api.expense.CreateExpenseRequest;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class CorrelationIdFilterIT extends AbstractPostgresIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void captureLogs() {
    logAppender = new ListAppender<>();
    logAppender.start();
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logAppender);
  }

  @AfterEach
  void stopCapturingLogs() {
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logAppender);
  }

  @Test
  void responseCarriesAGeneratedCorrelationIdWhenClientSuppliesNone() throws Exception {
    MvcResult result = mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andReturn();

    String correlationId = result.getResponse().getHeader(CorrelationIdFilter.HEADER);
    assertThat(correlationId).isNotBlank();
  }

  @Test
  void clientSuppliedUuidCorrelationIdIsPropagatedRatherThanOverwritten() throws Exception {
    String clientSuppliedId = "3d3811bc-6353-4d0b-864a-7ed86ae97ece";

    MvcResult result =
        mockMvc
            .perform(get("/actuator/health").header(CorrelationIdFilter.HEADER, clientSuppliedId))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getHeader(CorrelationIdFilter.HEADER)).isEqualTo(clientSuppliedId);
  }

  @Test
  void unsafeClientSuppliedCorrelationIdIsReplacedBeforeLogging() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get("/actuator/health")
                    .header(CorrelationIdFilter.HEADER, "Bearer service-token-123"))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getHeader(CorrelationIdFilter.HEADER))
        .isNotEqualTo("Bearer service-token-123");
  }

  @Test
  void allLogLinesForOneRequestShareOneCorrelationId() throws Exception {
    String email = "correlation-user-" + System.nanoTime() + "@example.com";
    MvcResult registerResult =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new RegisterRequest("org-" + System.nanoTime(), email, "correct-horse-battery"))))
            .andExpect(status().isCreated())
            .andReturn();

    String correlationId = registerResult.getResponse().getHeader(CorrelationIdFilter.HEADER);
    assertThat(correlationId).isNotBlank();

    AuthResponse auth =
        objectMapper.readValue(registerResult.getResponse().getContentAsString(), AuthResponse.class);

    logAppender.list.clear();

    MvcResult expenseResult =
        mockMvc
            .perform(
                post("/api/v1/expenses")
                    .header("Authorization", "Bearer " + auth.accessToken())
                    .header("Idempotency-Key", "key-" + System.nanoTime())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new CreateExpenseRequest(1500, "EUR"))))
            .andExpect(status().isCreated())
            .andReturn();

    String expenseCorrelationId = expenseResult.getResponse().getHeader(CorrelationIdFilter.HEADER);
    assertThat(expenseCorrelationId).isNotBlank();

    List<String> mdcCorrelationIdsSeen =
        logAppender.list.stream()
            .map(event -> event.getMDCPropertyMap().get(CorrelationIdHolder.MDC_KEY))
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();

    assertThat(mdcCorrelationIdsSeen).containsExactly(expenseCorrelationId);
  }
}
