package com.ledgerly.api;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
    })
@AutoConfigureMockMvc
class ApiApplicationTests {

  @Autowired private MockMvc mockMvc;

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
  void unknownRouteReturnsProblemDetailJsonWithNoStackTrace() throws Exception {
    mockMvc
        .perform(get("/does-not-exist"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status", is(404)))
        .andExpect(jsonPath("$.detail").exists())
        .andExpect(jsonPath("$.trace").doesNotExist());
  }
}
