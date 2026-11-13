package com.asg.edgeplus.platform.tenancy;

import java.time.Duration;
import java.util.List;

/** Builds {@link TenancyProperties} for tests; nothing here is a real credential. */
final class TestProperties {

  static final String URL_TEMPLATE = "jdbc:postgresql://localhost:5432/{db}";

  /** A fully valid configuration; tests override one field at a time via the wither methods. */
  static TenancyProperties valid() {
    return new TenancyProperties(
        null,
        URL_TEMPLATE,
        "asg_spoke_{tenant}",
        "lease",
        "svc_lease",
        "test-only-pw",
        "db/changelog/test-lease.yaml",
        null,
        null,
        null,
        null,
        null);
  }

  static TenancyProperties withUrlTemplate(String urlTemplate) {
    TenancyProperties v = valid();
    return new TenancyProperties(
        v.enabled(), urlTemplate, v.databaseNameTemplate(), v.schema(), v.username(),
        v.password(), v.changelog(), v.adminUsername(), v.adminPassword(), v.poolMaxSize(),
        v.poolIdleTimeout(), v.platformScopeRoles());
  }

  static TenancyProperties withSchema(String schema) {
    TenancyProperties v = valid();
    return new TenancyProperties(
        v.enabled(), v.jdbcUrlTemplate(), v.databaseNameTemplate(), schema, v.username(),
        v.password(), v.changelog(), v.adminUsername(), v.adminPassword(), v.poolMaxSize(),
        v.poolIdleTimeout(), v.platformScopeRoles());
  }

  static TenancyProperties withCredentials(String username, String password) {
    TenancyProperties v = valid();
    return new TenancyProperties(
        v.enabled(), v.jdbcUrlTemplate(), v.databaseNameTemplate(), v.schema(), username,
        password, v.changelog(), v.adminUsername(), v.adminPassword(), v.poolMaxSize(),
        v.poolIdleTimeout(), v.platformScopeRoles());
  }

  static TenancyProperties withChangelog(String changelog) {
    TenancyProperties v = valid();
    return new TenancyProperties(
        v.enabled(), v.jdbcUrlTemplate(), v.databaseNameTemplate(), v.schema(), v.username(),
        v.password(), changelog, v.adminUsername(), v.adminPassword(), v.poolMaxSize(),
        v.poolIdleTimeout(), v.platformScopeRoles());
  }

  static TenancyProperties withAdmin(String adminUsername, String adminPassword) {
    TenancyProperties v = valid();
    return new TenancyProperties(
        v.enabled(), v.jdbcUrlTemplate(), v.databaseNameTemplate(), v.schema(), v.username(),
        v.password(), v.changelog(), adminUsername, adminPassword, v.poolMaxSize(),
        v.poolIdleTimeout(), v.platformScopeRoles());
  }

  static TenancyProperties withPool(Integer maxSize, Duration idleTimeout) {
    TenancyProperties v = valid();
    return new TenancyProperties(
        v.enabled(), v.jdbcUrlTemplate(), v.databaseNameTemplate(), v.schema(), v.username(),
        v.password(), v.changelog(), v.adminUsername(), v.adminPassword(), maxSize, idleTimeout,
        v.platformScopeRoles());
  }

  static TenancyProperties withPlatformRoles(List<String> roles) {
    TenancyProperties v = valid();
    return new TenancyProperties(
        v.enabled(), v.jdbcUrlTemplate(), v.databaseNameTemplate(), v.schema(), v.username(),
        v.password(), v.changelog(), v.adminUsername(), v.adminPassword(), v.poolMaxSize(),
        v.poolIdleTimeout(), roles);
  }

  static TenancyProperties withMigration(String migrationUsername, String migrationPassword) {
    TenancyProperties v = valid();
    return new TenancyProperties(
        v.enabled(), v.jdbcUrlTemplate(), v.databaseNameTemplate(), v.schema(), v.username(),
        v.password(), v.changelog(), v.adminUsername(), v.adminPassword(), v.poolMaxSize(),
        v.poolIdleTimeout(), v.platformScopeRoles(), migrationUsername, migrationPassword);
  }

  private TestProperties() {}
}
