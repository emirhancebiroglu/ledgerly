package com.ledgerly.api.auth;

import com.ledgerly.api.idempotency.IdempotencyFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * Both filters are wired explicitly into the security chain below. Without this, Spring Boot's
   * generic servlet-filter auto-registration would ALSO register them directly with the
   * container, running each one twice per request.
   */
  @Bean
  public FilterRegistrationBean<JwtAuthenticationFilter> disableJwtFilterAutoRegistration(
      JwtAuthenticationFilter filter) {
    FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  public FilterRegistrationBean<IdempotencyFilter> disableIdempotencyFilterAutoRegistration(
      IdempotencyFilter filter) {
    FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http, JwtAuthenticationFilter jwtFilter, IdempotencyFilter idempotencyFilter)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Without this, Spring Security answers an anonymous request to a protected endpoint with
        // 403, which tells a client "you may not" when the truth is "you did not say who you are".
        // A caller holding no credentials needs 401 to know that presenting some would help.
        .exceptionHandling(
            exceptions ->
                exceptions.authenticationEntryPoint(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/api/v1/auth/**", "/actuator/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(idempotencyFilter, AuthorizationFilter.class);
    return http.build();
  }
}
