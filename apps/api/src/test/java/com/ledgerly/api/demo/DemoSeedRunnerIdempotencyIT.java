package com.ledgerly.api.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.api.ledger.AbstractPostgresIT;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * T5.5's test criterion: running the seed twice against the same database creates nothing new.
 * The context's own startup already ran it once (see {@link DemoSeedRunnerIT}); this calls
 * {@link DemoSeedRunner#run} again directly against the already-seeded database and asserts the
 * counts don't move.
 */
// application-demo.yml already raises the policy-upload quota for this profile; no property
// override needed here.
@SpringBootTest
@ActiveProfiles("demo")
class DemoSeedRunnerIdempotencyIT extends AbstractPostgresIT {

  @Autowired private DataSource dataSource;
  @Autowired private DemoSeedRunner demoSeedRunner;

  @Test
  void runningTheSeedTwiceCreatesNoDuplicateRows() throws Exception {
    int documentsBefore = countDocuments();
    int expensesBefore = countExpenses();
    int budgetsBefore = countBudgets();

    demoSeedRunner.run(new DefaultApplicationArguments());

    assertThat(countDocuments()).isEqualTo(documentsBefore);
    assertThat(countExpenses()).isEqualTo(expensesBefore);
    assertThat(countBudgets()).isEqualTo(budgetsBefore);
  }

  private int countDocuments() throws Exception {
    return scalar(
        "SELECT count(*) FROM document d JOIN organization o ON o.id = d.organization_id "
            + "WHERE o.name = 'Ledgerly Demo Co.'");
  }

  private int countExpenses() throws Exception {
    return scalar(
        "SELECT count(*) FROM expense e JOIN organization o ON o.id = e.organization_id "
            + "WHERE o.name = 'Ledgerly Demo Co.'");
  }

  private int countBudgets() throws Exception {
    return scalar(
        "SELECT count(*) FROM budget b JOIN organization o ON o.id = b.organization_id "
            + "WHERE o.name = 'Ledgerly Demo Co.'");
  }

  private int scalar(String sql) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement ps = connection.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
