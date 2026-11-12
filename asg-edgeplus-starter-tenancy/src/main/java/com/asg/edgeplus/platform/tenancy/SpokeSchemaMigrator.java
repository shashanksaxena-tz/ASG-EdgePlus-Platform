package com.asg.edgeplus.platform.tenancy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes one spoke ready for this service: creates the service's schema if absent, then runs the
 * service's own changelog inside it (ADR-0058, option (a)).
 *
 * <p><b>The schema is the isolation boundary between services.</b> It is created owned by the
 * service login (via the optional provisioner credential) and given no grants to anyone else, so
 * another service's login is refused with {@code 42501} on it. Migration then runs as the
 * <em>service</em> login, not the provisioner, so every object belongs to the service too.
 *
 * <p><b>Each service has its own history.</b> Liquibase's {@code defaultSchemaName} and
 * {@code liquibaseSchemaName} are both the service schema, so {@code DATABASECHANGELOG} and its
 * lock table live there and two services never see, or lock, each other's changesets.
 *
 * <p><b>Concurrency.</b> Inside one JVM {@link SpokePools} already serialises per firm. Across
 * instances, schema creation is serialised by a Postgres advisory lock (two {@code CREATE SCHEMA IF
 * NOT EXISTS} can otherwise collide on {@code pg_namespace}) and the migration by Liquibase's own
 * changelog lock.
 *
 * <p>Plain {@link DriverManager} connections rather than a pool: this runs once per firm per
 * process, outside any transaction manager.
 */
public class SpokeSchemaMigrator implements SpokeProvisioner {

  private static final Logger log = LoggerFactory.getLogger(SpokeSchemaMigrator.class);

  /**
   * Liquibase's scope stack is process-wide state, so two threads running it at once in one JVM
   * fail with "Cannot end scope ... when currently at scope ...". Found by racing two firms' first
   * requests, not by reading the docs. One migration at a time per JVM is cheap (it runs once per
   * firm per process) and the Liquibase changelog lock still covers other instances.
   */
  private static final Object LIQUIBASE_JVM_LOCK = new Object();

  private final TenancyProperties properties;

  public SpokeSchemaMigrator(TenancyProperties properties) {
    this.properties = properties;
  }

  @Override
  public void ensureReady(String databaseName) {
    String url = properties.jdbcUrl(databaseName);
    try {
      createSchemaIfAbsent(url);
      migrate(url);
    } catch (SQLException | liquibase.exception.LiquibaseException e) {
      throw new IllegalStateException(
          "could not prepare schema '" + properties.schema() + "' in " + databaseName, e);
    }
    log.info("tenancy spoke {} ready for schema {}", databaseName, properties.schema());
  }

  private void createSchemaIfAbsent(String url) throws SQLException {
    String user = properties.hasAdmin() ? properties.adminUsername() : properties.username();
    String password = properties.hasAdmin() ? properties.adminPassword() : properties.password();
    try (Connection c = DriverManager.getConnection(url, user, password)) {
      c.setAutoCommit(true);
      lock(c);
      try {
        if (!schemaExists(c)) {
          String owner = properties.hasAdmin() ? " AUTHORIZATION " + quote(properties.username()) : "";
          try (Statement s = c.createStatement()) {
            s.executeUpdate("CREATE SCHEMA IF NOT EXISTS " + quote(properties.schema()) + owner);
          }
          log.info("tenancy created schema {}", properties.schema());
        }
      } finally {
        unlock(c);
      }
    }
  }

  private boolean schemaExists(Connection c) throws SQLException {
    // pg_namespace, not information_schema.schemata: the latter hides schemas the caller has no
    // privilege on, which would report a schema owned by someone else as absent.
    try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM pg_namespace WHERE nspname = ?")) {
      ps.setString(1, properties.schema());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private void lock(Connection c) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))")) {
      ps.setString(1, "asg-tenancy-schema:" + properties.schema());
      ps.execute();
    }
  }

  private void unlock(Connection c) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
      ps.setString(1, "asg-tenancy-schema:" + properties.schema());
      ps.execute();
    }
  }

  private void migrate(String url) throws SQLException, liquibase.exception.LiquibaseException {
    // The lock spans every use of the Liquibase API -- DatabaseFactory and the configuration reads
    // behind it touch the same scope stack as update() does, so locking update() alone still races.
    synchronized (LIQUIBASE_JVM_LOCK) {
      try (Connection c =
          DriverManager.getConnection(url, properties.username(), properties.password())) {
        Database db =
            DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
        db.setDefaultSchemaName(properties.schema());
        db.setLiquibaseSchemaName(properties.schema());
        try (Liquibase liquibase =
            new Liquibase(properties.changelog(), new ClassLoaderResourceAccessor(), db)) {
          liquibase.update(new Contexts(), new LabelExpression());
        }
      }
    }
  }

  /** Schema and role names are validated upstream; quoting is the second line of defence. */
  private static String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
