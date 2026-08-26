package com.ledgerly.api.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.api.ledger.AbstractPostgresIT;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * T5.5: the {@code demo} profile runs {@link DemoSeedRunner} automatically at startup — this
 * boots the real Spring context with that profile active and asserts what it left behind, rather
 * than calling the runner directly. {@code ai} is unreachable in this test environment
 * (test/application.yml points {@code ledgerly.ai.base-url} at a closed port), so the one real
 * network call {@link DemoSeedRunner} makes — the anomaly advisory explanation, fired
 * asynchronously off {@code ExpensePostedEvent} — degrades to no alert rather than failing the
 * seed; BUDGET_THRESHOLD and DUPLICATE_SUSPECTED don't depend on {@code ai} and are asserted
 * here instead.
 */
// application-demo.yml already raises the policy-upload quota for this profile; no property
// override needed here.
@SpringBootTest
@ActiveProfiles("demo")
class DemoSeedRunnerIT extends AbstractPostgresIT {

  @Autowired private DataSource dataSource;

  @Test
  void seedsExactlyOneDemoOrganizationWithAllInvoicesPoliciesAndABudget() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      assertThat(scalar(connection, "SELECT count(*) FROM organization WHERE name = 'Ledgerly Demo Co.'"))
          .isEqualTo(1);
      assertThat(
              scalar(
                  connection,
                  "SELECT count(*) FROM document d JOIN organization o ON o.id = d.organization_id "
                      + "WHERE o.name = 'Ledgerly Demo Co.'"))
          .isEqualTo(21);
      assertThat(
              scalar(
                  connection,
                  "SELECT count(*) FROM expense e JOIN organization o ON o.id = e.organization_id "
                      + "WHERE o.name = 'Ledgerly Demo Co.'"))
          .isEqualTo(21);
      assertThat(
              scalar(
                  connection,
                  "SELECT count(*) FROM policy_document pd JOIN organization o ON o.id = pd.organization_id "
                      + "WHERE o.name = 'Ledgerly Demo Co.'"))
          .isEqualTo(3);
      assertThat(
              scalar(
                  connection,
                  "SELECT count(*) FROM budget b JOIN organization o ON o.id = b.organization_id "
                      + "WHERE o.name = 'Ledgerly Demo Co.'"))
          .isEqualTo(1);
    }
  }

  @Test
  void budgetThresholdAndDuplicateSuspectedAlertsFireWithoutAnyAiCall() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      assertThat(
              scalar(
                  connection,
                  "SELECT count(*) FROM alert a JOIN organization o ON o.id = a.organization_id "
                      + "WHERE o.name = 'Ledgerly Demo Co.' AND a.alert_type = 'BUDGET_THRESHOLD'"))
          .isEqualTo(2);
      assertThat(
              scalar(
                  connection,
                  "SELECT count(*) FROM alert a JOIN organization o ON o.id = a.organization_id "
                      + "WHERE o.name = 'Ledgerly Demo Co.' AND a.alert_type = 'DUPLICATE_SUSPECTED'"))
          .isEqualTo(1);
    }
  }

  private int scalar(Connection connection, String sql) throws Exception {
    try (PreparedStatement ps = connection.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
