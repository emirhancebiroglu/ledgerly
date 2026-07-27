package com.ledgerly.api.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

/**
 * M5.1 T5: {@code X-Content-Type-Options: nosniff} is what stops a polyglot upload (valid magic
 * bytes, an HTML payload after the header) from being executed if a downstream consumer ever
 * renders stored bytes inline. Explicitly configured in {@link SecurityConfig#filterChain} rather
 * than relied on as a Spring Security default — this test is what makes that configuration
 * load-bearing: disable it in {@code SecurityConfig} and this fails.
 */
@AutoConfigureMockMvc
class SecurityHeadersIT extends AbstractPostgresIT {

  @Autowired private org.springframework.test.web.servlet.MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void anAnonymouslyAccessibleEndpointStillCarriesNosniff() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new RegisterRequest(
                            "org-" + System.nanoTime(),
                            "nosniff-" + System.nanoTime() + "@example.com",
                            "correct-horse-battery"))))
        .andExpect(status().isCreated())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
  }

  @Test
  void anUnauthenticatedRejectionStillCarriesNosniff() throws Exception {
    mockMvc
        .perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
  }
}
