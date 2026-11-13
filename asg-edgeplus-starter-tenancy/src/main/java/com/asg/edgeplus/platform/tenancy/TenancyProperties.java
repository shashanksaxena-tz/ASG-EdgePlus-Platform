package com.asg.edgeplus.platform.tenancy;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Configuration for firm-to-spoke routing. Everything a service sets to use the starter.
 *
 * <p>There are <strong>no default credentials and no default host</strong>: {@code username},
 * {@code password} and {@code jdbc-url-template} come from the environment (Constitution §4.12) and
 * a service that forgets one fails at startup, naming the property, in {@link #validate()}.
 *
 * @param enabled turn the starter off entirely, e.g. for a slice test with no database
 * @param jdbcUrlTemplate the JDBC URL of a spoke with {@code {db}} where the database name goes,
 *     e.g. {@code jdbc:postgresql://host:5432/{db}}
 * @param databaseNameTemplate how a firm maps to its database, with {@code {tenant}} for the firm
 *     id. Ignored when the service declares its own {@link SpokeLocator} bean — required otherwise
 * @param schema this service's schema inside every spoke ({@code lease}, {@code accounting}); also
 *     where its Liquibase history lives
 * @param username this service's database login, used by every request and job connection. In
 *     single-login mode (no {@code migrationUsername}) it also owns the {@code schema}; in two-login
 *     mode it is <em>not</em> the owner and can do only what the service's changesets grant it
 * @param password the password for {@code username}
 * @param changelog classpath path of this service's Liquibase master changelog
 * @param adminUsername optional provisioner login used only to {@code CREATE SCHEMA ... AUTHORIZATION
 *     <username>} on first use. Without it the service login creates the schema itself, which needs
 *     {@code CREATE} on the database
 * @param adminPassword password for {@code adminUsername}
 * @param poolMaxSize connections per firm pool (default 3). Twenty-one firms at ten connections
 *     each is 210 against a Postgres default of 100
 * @param poolIdleTimeout how long an idle connection lives before being closed (default 5 minutes)
 * @param platformScopeRoles roles allowed to request platform scope ({@code X-Tenant-Scope:
 *     platform}). Empty by default, which means platform scope is refused for everyone
 * @param migrationUsername optional login that owns the {@code schema} and runs the changelog
 *     (two-login mode, ADR-0058 amendment 2026-11-13). Set it when grants must be a real barrier:
 *     an owner holds every privilege on its tables and can drop their triggers, so a runtime login
 *     that is not the owner is the only way to make, say, an INSERT-only table INSERT-only. The
 *     starter grants {@code USAGE} on the schema to {@code username}; table grants are the
 *     service's own changesets, which receive the runtime login as the changelog parameter
 *     {@code runtimeLogin}
 * @param migrationPassword password for {@code migrationUsername}
 */
@ConfigurationProperties(prefix = "asg.platform.tenancy")
public record TenancyProperties(
    Boolean enabled,
    String jdbcUrlTemplate,
    String databaseNameTemplate,
    String schema,
    String username,
    String password,
    String changelog,
    String adminUsername,
    String adminPassword,
    Integer poolMaxSize,
    Duration poolIdleTimeout,
    List<String> platformScopeRoles,
    String migrationUsername,
    String migrationPassword) {

  private static final String PREFIX = "asg.platform.tenancy.";
  private static final int DEFAULT_POOL_MAX_SIZE = 3;
  private static final Duration DEFAULT_POOL_IDLE_TIMEOUT = Duration.ofMinutes(5);

  /** Postgres identifiers are at most 63 bytes; lower-case so no quoting subtleties arise. */
  private static final Pattern SAFE_SCHEMA = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

  /**
   * Repeated wherever a database name is concatenated into SQL or a URL, at the point of use: the
   * only safe concatenation is one validated immediately before it.
   */
  static final Pattern SAFE_DB_NAME = Pattern.compile("^asg_spoke_[a-z0-9_]{1,40}$");

  @ConstructorBinding
  public TenancyProperties {
    enabled = enabled == null ? Boolean.TRUE : enabled;
    poolMaxSize = poolMaxSize == null ? DEFAULT_POOL_MAX_SIZE : poolMaxSize;
    poolIdleTimeout = poolIdleTimeout == null ? DEFAULT_POOL_IDLE_TIMEOUT : poolIdleTimeout;
    platformScopeRoles = platformScopeRoles == null ? List.of() : List.copyOf(platformScopeRoles);
  }

  /** Single-login form: the service login owns and uses the schema. */
  public TenancyProperties(
      Boolean enabled,
      String jdbcUrlTemplate,
      String databaseNameTemplate,
      String schema,
      String username,
      String password,
      String changelog,
      String adminUsername,
      String adminPassword,
      Integer poolMaxSize,
      Duration poolIdleTimeout,
      List<String> platformScopeRoles) {
    this(
        enabled, jdbcUrlTemplate, databaseNameTemplate, schema, username, password, changelog,
        adminUsername, adminPassword, poolMaxSize, poolIdleTimeout, platformScopeRoles, null, null);
  }

  public boolean isEnabled() {
    return Boolean.TRUE.equals(enabled);
  }

  /** {@code true} when a distinct provisioner login was configured. */
  public boolean hasAdmin() {
    return !isBlank(adminUsername) && !isBlank(adminPassword);
  }

  /** {@code true} when a distinct migration login owns the schema (two-login mode). */
  public boolean hasMigrationLogin() {
    return !isBlank(migrationUsername) && !isBlank(migrationPassword);
  }

  /** The login that owns the schema and runs the changelog. */
  public String schemaOwnerUsername() {
    return hasMigrationLogin() ? migrationUsername : username;
  }

  /** Password of {@link #schemaOwnerUsername()}. */
  String schemaOwnerPassword() {
    return hasMigrationLogin() ? migrationPassword : password;
  }

  /** The JDBC URL of one spoke. Refuses any name outside the {@code asg_spoke_*} namespace. */
  String jdbcUrl(String databaseName) {
    if (databaseName == null || !SAFE_DB_NAME.matcher(databaseName).matches()) {
      throw new IllegalArgumentException("refusing unsafe spoke database name: " + databaseName);
    }
    return jdbcUrlTemplate.replace("{db}", databaseName);
  }

  /**
   * Fails fast, naming the property, so a mis-set service dies at startup rather than on the first
   * firm's first request.
   */
  void validate() {
    require(jdbcUrlTemplate, "jdbc-url-template");
    if (!jdbcUrlTemplate.contains("{db}")) {
      throw misconfigured("jdbc-url-template must contain {db}, where the database name goes");
    }
    require(schema, "schema");
    if (!SAFE_SCHEMA.matcher(schema).matches() || isReservedSchema(schema)) {
      throw misconfigured(
          "schema '" + schema + "' is not usable: lower-case letters, digits and underscores,"
              + " starting with a letter, at most 63 characters, and not a Postgres system schema"
              + " or public");
    }
    require(username, "username");
    require(password, "password");
    require(changelog, "changelog");
    if (isBlank(adminUsername) != isBlank(adminPassword)) {
      throw misconfigured("admin-username and admin-password must be set together or not at all");
    }
    if (isBlank(migrationUsername) != isBlank(migrationPassword)) {
      throw misconfigured(
          "migration-username and migration-password must be set together or not at all");
    }
    if (hasMigrationLogin() && migrationUsername.equals(username)) {
      throw misconfigured(
          "migration-username must differ from username: the runtime login must not own the schema");
    }
    requireSafeLogin(username, "username");
    requireSafeLogin(adminUsername, "admin-username");
    requireSafeLogin(migrationUsername, "migration-username");
    if (poolMaxSize < 1) {
      throw misconfigured("pool-max-size must be at least 1");
    }
    if (poolIdleTimeout.isZero() || poolIdleTimeout.isNegative()) {
      throw misconfigured("pool-idle-timeout must be positive");
    }
  }

  /**
   * Login names reach SQL as identifiers, and {@code username} reaches every service changeset as
   * the raw {@code ${runtimeLogin}} parameter. Lower-case only, so a changeset that forgets to quote
   * it still names the same role.
   */
  private static void requireSafeLogin(String login, String name) {
    if (!isBlank(login) && !SAFE_SCHEMA.matcher(login).matches()) {
      throw misconfigured(
          name + " '" + login + "' is not usable: lower-case letters, digits and underscores,"
              + " starting with a letter, at most 63 characters");
    }
  }

  private static boolean isReservedSchema(String schema) {
    return schema.equals("public") || schema.startsWith("pg_") || schema.equals("information_schema");
  }

  private static void require(String value, String name) {
    if (isBlank(value)) {
      throw misconfigured(name + " is required");
    }
  }

  private static IllegalStateException misconfigured(String detail) {
    // The prefix is on every message so the property can be found by grepping the log.
    return new IllegalStateException(PREFIX + detail);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /**
   * Passwords masked: the generated record {@code toString()} would print every credential, and a
   * properties object ends up in a log or an exception message sooner or later (Constitution 4.12).
   */
  @Override
  public String toString() {
    return "TenancyProperties[enabled=" + enabled
        + ", jdbcUrlTemplate=" + jdbcUrlTemplate
        + ", databaseNameTemplate=" + databaseNameTemplate
        + ", schema=" + schema
        + ", username=" + username
        + ", password=" + mask(password)
        + ", changelog=" + changelog
        + ", adminUsername=" + adminUsername
        + ", adminPassword=" + mask(adminPassword)
        + ", poolMaxSize=" + poolMaxSize
        + ", poolIdleTimeout=" + poolIdleTimeout
        + ", platformScopeRoles=" + platformScopeRoles
        + ", migrationUsername=" + migrationUsername
        + ", migrationPassword=" + mask(migrationPassword)
        + "]";
  }

  private static String mask(String secret) {
    return secret == null ? "null" : "****";
  }
}
