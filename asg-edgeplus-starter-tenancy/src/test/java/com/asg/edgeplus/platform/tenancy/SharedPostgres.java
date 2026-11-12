package com.asg.edgeplus.platform.tenancy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres for the whole integration suite. Each test creates its own uniquely named
 * databases, so sharing the instance costs no isolation and saves a container start per class.
 */
final class SharedPostgres {

  static final String ADMIN_USER = "postgres_admin";
  static final String ADMIN_PASSWORD = "admin-test-only";

  private static final PostgreSQLContainer<?> CONTAINER =
      new PostgreSQLContainer<>("postgres:16")
          .withUsername(ADMIN_USER)
          .withPassword(ADMIN_PASSWORD)
          .withDatabaseName("postgres");

  static {
    CONTAINER.start();
  }

  /** {@code jdbc:postgresql://host:port/{db}} against the container. */
  static String urlTemplate() {
    return "jdbc:postgresql://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(5432) + "/{db}";
  }

  static String url(String db) {
    return urlTemplate().replace("{db}", db);
  }

  static void createDatabase(String name) {
    execute("postgres", "CREATE DATABASE " + name);
  }

  static void createLogin(String role, String password) {
    execute("postgres", "CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'");
  }

  static void execute(String db, String sql) {
    try (Connection c = DriverManager.getConnection(url(db), ADMIN_USER, ADMIN_PASSWORD);
        Statement s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(sql, e);
    }
  }

  static Connection admin(String db) throws SQLException {
    return DriverManager.getConnection(url(db), ADMIN_USER, ADMIN_PASSWORD);
  }

  private SharedPostgres() {}
}
