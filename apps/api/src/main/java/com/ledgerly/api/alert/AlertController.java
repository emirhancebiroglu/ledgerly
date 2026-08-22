package com.ledgerly.api.alert;

import com.ledgerly.api.auth.AuthenticatedPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AlertController {
  private final AlertRepository alertRepository;
  private final AlertTitleResolver alertTitleResolver;

  public AlertController(AlertRepository alertRepository, AlertTitleResolver alertTitleResolver) {
    this.alertRepository = alertRepository;
    this.alertTitleResolver = alertTitleResolver;
  }

  @GetMapping("/api/v1/alerts")
  public List<AlertResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return alertRepository.findByOrganizationId(principal.organizationId(),
        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))).stream()
        .map(alert -> AlertResponse.from(alert, alertTitleResolver.resolve(alert)))
        .toList();
  }
}
