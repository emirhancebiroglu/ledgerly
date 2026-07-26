package com.ledgerly.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

class ChartOfAccountsSeedIT extends AbstractPostgresIT {

  @Autowired
  private DataSource dataSource;

  @Test
  void seedLoadsAllFiveAccountTypesOrgScopedAndNotOrphaned() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(
            """
            SELECT a.account_type
            FROM account a
            JOIN organization o ON o.id = a.organization_id
            """)) {
      Set<String> types = new HashSet<>();
      while (resultSet.next()) {
        types.add(resultSet.getString("account_type"));
      }
      assertThat(types).containsExactlyInAnyOrder(
          "ASSET", "LIABILITY", "EXPENSE", "REVENUE", "EQUITY");
    }

    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(
            """
            SELECT count(*) FROM account a
            LEFT JOIN organization o ON o.id = a.organization_id
            WHERE o.id IS NULL
            """)) {
      resultSet.next();
      assertThat(resultSet.getInt(1)).as("orphaned accounts").isZero();
    }
  }

  @Test
  void reapplyingSeedStatementsIsIdempotent() throws Exception {
    String seedSql = Files.readString(
        Path.of(new ClassPathResource("db/migration/V4__chart_of_accounts_seed.sql").getURI()));

    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      // simulate a manual re-apply of the same seed statements
      statement.execute(seedSql);
      statement.execute(seedSql);

      try (ResultSet resultSet = statement.executeQuery(
          "SELECT count(*) FROM account WHERE organization_id = "
              + "'00000000-0000-0000-0000-000000000001'")) {
        resultSet.next();
        assertThat(resultSet.getInt(1)).isEqualTo(5);
      }
    }
  }
}
