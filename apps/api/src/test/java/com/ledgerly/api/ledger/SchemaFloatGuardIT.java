package com.ledgerly.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Flyway migrations run clean against a real Postgres 17 (via {@link AbstractPostgresIT}), and
 * no column anywhere in the schema uses a floating-point type — money is BIGINT + CHAR(3) only.
 */
class SchemaFloatGuardIT extends AbstractPostgresIT {

  @Autowired
  private DataSource dataSource;

  @Test
  void noFloatingPointColumnsExistInAnyTable() throws Exception {
    List<String> offenders = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(
            """
            SELECT table_name, column_name, data_type
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND data_type IN ('real', 'double precision', 'float4', 'float8')
            """)) {
      while (resultSet.next()) {
        offenders.add(resultSet.getString("table_name") + "." + resultSet.getString("column_name")
            + " (" + resultSet.getString("data_type") + ")");
      }
    }

    assertThat(offenders).as("floating-point columns found in schema").isEmpty();
  }

  @Test
  void everyMoneyColumnIsBigintWithCharThreeCurrency() throws Exception {
    List<String> moneyColumns = List.of(
        "ledger_entry.native_amount_minor", "ledger_entry.base_amount_minor");
    List<String> currencyColumns = List.of(
        "ledger_entry.native_currency", "ledger_entry.base_currency",
        "account.currency", "organization.base_currency", "ledger_transaction.base_currency",
        "fx_rate.from_currency", "fx_rate.to_currency");

    for (String qualified : moneyColumns) {
      assertThat(columnType(qualified)).isEqualToIgnoringCase("bigint");
    }
    for (String qualified : currencyColumns) {
      assertThat(columnType(qualified)).isEqualToIgnoringCase("character");
      assertThat(columnLength(qualified)).isEqualTo(3);
    }
  }

  @Test
  void fxRateHoldsEightDecimalPlacesWithoutLoss() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(
            """
            SELECT numeric_precision, numeric_scale
            FROM information_schema.columns
            WHERE table_name = 'fx_rate' AND column_name = 'rate'
            """)) {
      assertThat(resultSet.next()).isTrue();
      assertThat(resultSet.getInt("numeric_scale")).isGreaterThanOrEqualTo(8);
    }
  }

  private String columnType(String qualifiedColumn) throws Exception {
    String[] parts = qualifiedColumn.split("\\.", 2);
    try (Connection connection = dataSource.getConnection();
        var ps = connection.prepareStatement(
            """
            SELECT data_type FROM information_schema.columns
            WHERE table_name = ? AND column_name = ?
            """)) {
      ps.setString(1, parts[0]);
      ps.setString(2, parts[1]);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("column %s must exist", qualifiedColumn).isTrue();
        return rs.getString("data_type");
      }
    }
  }

  private int columnLength(String qualifiedColumn) throws Exception {
    String[] parts = qualifiedColumn.split("\\.", 2);
    try (Connection connection = dataSource.getConnection();
        var ps = connection.prepareStatement(
            """
            SELECT character_maximum_length FROM information_schema.columns
            WHERE table_name = ? AND column_name = ?
            """)) {
      ps.setString(1, parts[0]);
      ps.setString(2, parts[1]);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getInt("character_maximum_length");
      }
    }
  }
}
