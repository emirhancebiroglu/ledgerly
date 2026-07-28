package com.ledgerly.api.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Test-only counter for every JDBC statement executed by the production request/worker path. */
public final class SqlStatementCounter {

  private static final AtomicLong EXECUTED = new AtomicLong();

  private SqlStatementCounter() {}

  public static void reset() {
    EXECUTED.set(0);
  }

  public static long executed() {
    return EXECUTED.get();
  }

  public static DataSource wrap(DataSource target) {
    return new CountingDataSource(target);
  }

  private static final class CountingDataSource extends DelegatingDataSource {

    private CountingDataSource(DataSource target) {
      super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
      return connection(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return connection(super.getConnection(username, password));
    }
  }

  private static Connection connection(Connection target) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              Object result = invoke(target, method.getName(), method.getParameterTypes(), args);
              return switch (method.getName()) {
                case "createStatement" -> statement((Statement) result);
                case "prepareStatement" -> preparedStatement((PreparedStatement) result);
                case "prepareCall" -> callableStatement((CallableStatement) result);
                default -> result;
              };
            });
  }

  private static Statement statement(Statement target) {
    return statementProxy(target, Statement.class);
  }

  private static PreparedStatement preparedStatement(PreparedStatement target) {
    return statementProxy(target, PreparedStatement.class);
  }

  private static CallableStatement callableStatement(CallableStatement target) {
    return statementProxy(target, CallableStatement.class);
  }

  @SuppressWarnings("unchecked")
  private static <T extends Statement> T statementProxy(T target, Class<T> type) {
    return (T)
        Proxy.newProxyInstance(
            Statement.class.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              if (method.getName().startsWith("execute")) {
                EXECUTED.incrementAndGet();
              }
              return invoke(target, method.getName(), method.getParameterTypes(), args);
            });
  }

  private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object[] args)
      throws Throwable {
    try {
      return target.getClass().getMethod(name, parameterTypes).invoke(target, args);
    } catch (InvocationTargetException exception) {
      throw exception.getCause();
    }
  }
}
