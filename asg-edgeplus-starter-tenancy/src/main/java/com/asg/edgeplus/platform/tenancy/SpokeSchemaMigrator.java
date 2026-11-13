package com.asg.edgeplus.platform.tenancy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import liquibase.Scope;
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
 * <p><b>Bounded waits.</b> A changelog lock left set by a crashed instance must not stall other
 * firms: the wait for it happens <em>before</em> the JVM-wide Liquibase lock is taken, by polling
 * {@code DATABASECHANGELOGLOCK}, and gives up after {@code lockTimeout}. The JVM-wide lock is itself
 * taken with the same timeout, Liquibase's own lock wait is shortened to one minute for the narrow
 * window between the poll and the update, and every connection has connect and login timeouts.
 * A timeout fails this request (the next one retries); it never hangs a request thread.
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
  private static final ReentrantLock LIQUIBASE_JVM_LOCK = new ReentrantLock();

  static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofMinutes(2);
  private static final String CONNECT_TIMEOUT_SECONDS = "10";
  private static final long POLL_MILLIS = 250;

  private final TenancyProperties properties;
  private final Duration lockTimeout;

  public SpokeSchemaMigrator(TenancyProperties properties) {
    this(properties, DEFAULT_LOCK_TIMEOUT);
  }

  SpokeSchemaMigrator(TenancyProperties properties, Duration lockTimeout) {
    this.properties = properties;
    this.lockTimeout = lockTimeout;
  }

  @Override
  public void ensureReady(String databaseName) {
    String url = properties.jdbcUrl(databaseName);
    try {
      createSchemaIfAbsent(url);
      migrate(url);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while preparing " + databaseName, e);
    } catch (Exception e) {
      throw new IllegalStateException(
          "could not prepare schema '" + properties.schema() + "' in " + databaseName, e);
    }
    log.info("tenancy spoke {} ready for schema {}", databaseName, properties.schema());
  }

  private void createSchemaIfAbsent(String url) throws SQLException {
    String user = properties.hasAdmin() ? properties.adminUsername() : properties.username();
    String password = properties.hasAdmin() ? properties.adminPassword() : properties.password();
    try (Connection c = connect(url, user, password)) {
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

  private void migrate(String url) throws Exception {
    String user = properties.username();
    String password = properties.password();
    awaitChangelogUnlocked(url, user, password);
    // The lock spans every use of the Liquibase API -- DatabaseFactory and the configuration reads
    // behind it touch the same scope stack as update() does, so locking update() alone still races.
    if (!LIQUIBASE_JVM_LOCK.tryLock(lockTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
      throw new IllegalStateException(
          "another firm's migration held this JVM's Liquibase lock for longer than " + lockTimeout);
    }
    try (Connection c = connect(url, user, password)) {
      Database db =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      db.setDefaultSchemaName(properties.schema());
      db.setLiquibaseSchemaName(properties.schema());
      try (Liquibase liquibase =
          new Liquibase(properties.changelog(), new ClassLoaderResourceAccessor(), db)) {
        Scope.child(
            Map.of("liquibase.changelogLockWaitTimeInMinutes", 1),
            () -> liquibase.update(new Contexts(), new LabelExpression()));
      }
    } finally {
      LIQUIBASE_JVM_LOCK.unlock();
    }
  }

  /**
   * Waits, without holding the JVM-wide lock, until no other instance holds this schema's changelog
   * lock. A lock left by a crashed instance then times out this firm alone.
   */
  private void awaitChangelogUnlocked(String url, String user, String password)
      throws SQLException, InterruptedException {
    long deadline = System.nanoTime() + lockTimeout.toNanos();
    try (Connection c = connect(url, user, password)) {
      while (changelogLocked(c)) {
        if (System.nanoTime() - deadline > 0) {
          throw new IllegalStateException(
              "the changelog lock of schema '"
                  + properties.schema()
                  + "' has been held for longer than "
                  + lockTimeout
                  + "; a crashed migration may have left DATABASECHANGELOGLOCK set");
        }
        Thread.sleep(POLL_MILLIS);
      }
    }
  }

  private boolean changelogLocked(Connection c) throws SQLException {
    String table = quote(properties.schema()) + ".databasechangeloglock";
    try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        if (!rs.getBoolean(1)) {
          return false; // first migration of this schema: no lock table yet
        }
      }
    }
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT coalesce(bool_or(locked), false) FROM " + table)) {
      rs.next();
      return rs.getBoolean(1);
    }
  }

  private static Connection connect(String url, String user, String password) throws SQLException {
    Properties p = new Properties();
    p.setProperty("user", user);
    p.setProperty("password", password);
    p.setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS);
    p.setProperty("loginTimeout", CONNECT_TIMEOUT_SECONDS);
    return DriverManager.getConnection(url, p);
  }

  /** Schema and role names are validated upstream; quoting is the second line of defence. */
  private static String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
