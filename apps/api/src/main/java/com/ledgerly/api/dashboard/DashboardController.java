package com.ledgerly.api.dashboard;

import com.ledgerly.api.auth.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardController {

  private final DashboardService dashboardService;

  public DashboardController(DashboardService dashboardService) {
    this.dashboardService = dashboardService;
  }

  @GetMapping("/api/v1/dashboard/summary")
  public DashboardSummaryResponse summary(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return dashboardService.summary(principal);
  }
}
