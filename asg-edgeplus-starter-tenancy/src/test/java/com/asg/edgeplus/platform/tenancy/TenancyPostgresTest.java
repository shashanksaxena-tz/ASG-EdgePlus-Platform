package com.asg.edgeplus.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.concurrent.CompletableFuture;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.asg.edgeplus.platform.domain.primitives.TenantId;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The behaviours ADR-0058 promises, against a real Postgres: a firm per database, a schema + login
 * + changelog table per service inside it, migration on first use, and nothing visible across
 * schemas.
 */
@EnabledIfSystemProperty(named = "docker.available", matches = "true")
class TenancyPostgresTest {

  private static final String PW = "svc-test-only";

  private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  private final TenantId firmA = new TenantId(UUID.randomUUID());
  private final TenantId firmB = new TenantId(UUID.randomUUID());
  private final List<SpokePools> opened = new ArrayList<>();

  private String leaseLogin;
  private String acctLogin;

  @BeforeEach
  void roles() {
    leaseLogin = "svc_lease_" + suffix;
    acctLogin = "svc_acct_" + suffix;
    SharedPostgres.createLogin(leaseLogin, PW);
    SharedPostgres.createLogin(acctLogin, PW);
  }

  @AfterEach
  void close() {
    opened.forEach(SpokePools::close);
  }

  /** The database the locator maps a firm to. The hex-without-hyphens form is what the default template yields. */
  private String dbOf(TenantId firm) {
    return new TemplateSpokeLocator("asg_spoke_{tenant}").databaseName(firm);
  }

  private void provisionDatabase(TenantId firm) {
    SharedPostgres.createDatabase(dbOf(firm));
  }

  private TenancyProperties lease(String admin) {
    return new TenancyProperties(
        null, SharedPostgres.urlTemplate(), "asg_spoke_{tenant}", "lease", leaseLogin, PW,
        "db/changelog/test-lease.yaml", admin == null ? null : SharedPostgres.ADMIN_USER,
        admin, null, null, null);
  }

  private TenancyProperties accounting() {
    return new TenancyProperties(
        null, SharedPostgres.urlTemplate(), "asg_spoke_{tenant}", "accounting", acctLogin, PW,
        "db/changelog/test-accounting.yaml", SharedPostgres.ADMIN_USER,
        SharedPostgres.ADMIN_PASSWORD, null, null, null);
  }

  private SpokePools pools(TenancyProperties props) {
    SpokePools p =
        new SpokePools(
            new TemplateSpokeLocator(props.databaseNameTemplate()),
            new SpokeSchemaMigrator(props),
            props);
    opened.add(p);
    return p;
  }

  private JdbcTemplate jdbc(SpokePools p, TenantId firm) {
    return new JdbcTemplate(p.forFirm(firm));
  }

  private int count(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  // ---------------------------------------------------------------- isolation

  @Test
  void twoFirmsAreIsolatedFromEachOther() {
    provisionDatabase(firmA);
    provisionDatabase(firmB);
    SpokePools p = pools(lease(SharedPostgres.ADMIN_PASSWORD));

    jdbc(p, firmA).update("INSERT INTO widget(id, label) VALUES (?, 'a-only')", UUID.randomUUID());

    assertThat(jdbc(p, firmA).queryForObject("SELECT count(*) FROM widget", Integer.class)).isEqualTo(1);
    assertThat(jdbc(p, firmB).queryForObject("SELECT count(*) FROM widget", Integer.class)).isZero();
  }

  @Test
  void theRoutingDataSourceServesEachFirmFromItsOwnDatabaseUnderItsScope() {
    provisionDatabase(firmA);
    provisionDatabase(firmB);
    SpokePools p = pools(lease(SharedPostgres.ADMIN_PASSWORD));
    JdbcTemplate routed = new JdbcTemplate(new TenantRoutingDataSource(p, null));

    TenantScopes.runIn(
        TenantScope.firm(firmA),
        () -> routed.update("INSERT INTO widget(id, label) VALUES (?, 'a')", UUID.randomUUID()));

    String aDb =
        TenantScopes.callIn(
            TenantScope.firm(firmA),
            () -> routed.queryForObject("SELECT current_database()", String.class));
    Integer a =
        TenantScopes.callIn(
            TenantScope.firm(firmA),
            () -> routed.queryForObject("SELECT count(*) FROM widget", Integer.class));
    Integer b =
        TenantScopes.callIn(
            TenantScope.firm(firmB),
            () -> routed.queryForObject("SELECT count(*) FROM widget", Integer.class));
    assertThat(aDb).isEqualTo(dbOf(firmA));
    assertThat(a).isEqualTo(1);
    assertThat(b).isZero();
  }

  // ------------------------------------------------- schema + changelog per service

  @Test
  void eachServiceGetsItsOwnSchemaAndItsOwnChangelogTableInTheSameFirmDatabase() throws Exception {
    provisionDatabase(firmA);
    SpokePools leasePools = pools(lease(SharedPostgres.ADMIN_PASSWORD));
    SpokePools acctPools = pools(accounting());

    leasePools.forFirm(firmA);
    acctPools.forFirm(firmA);

    try (Connection c = SharedPostgres.admin(dbOf(firmA))) {
      assertThat(count(c, "SELECT count(*) FROM lease.databasechangelog")).isEqualTo(2);
      assertThat(count(c, "SELECT count(*) FROM accounting.databasechangelog")).isEqualTo(1);
      assertThat(count(c, "SELECT count(*) FROM lease.databasechangeloglock")).isEqualTo(1);
      assertThat(count(c, "SELECT count(*) FROM accounting.databasechangeloglock")).isEqualTo(1);
      assertThat(count(c, tableIn("lease", "widget"))).isEqualTo(1);
      assertThat(count(c, tableIn("accounting", "ledger_entry"))).isEqualTo(1);
      // Neither service's tables leak into the other's schema, nor into public.
      assertThat(count(c, tableIn("lease", "ledger_entry"))).isZero();
      assertThat(count(c, tableIn("accounting", "widget"))).isZero();
      assertThat(count(c, "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'"))
          .isZero();
    }
  }

  private static String tableIn(String schema, String table) {
    return "SELECT count(*) FROM information_schema.tables WHERE table_schema = '"
        + schema + "' AND table_name = '" + table + "'";
  }

  @Test
  void theServiceSchemaIsOwnedByTheServiceLoginWhenAProvisionerCreatesIt() throws Exception {
    provisionDatabase(firmA);
    pools(lease(SharedPostgres.ADMIN_PASSWORD)).forFirm(firmA);

    try (Connection c = SharedPostgres.admin(dbOf(firmA))) {
      try (Statement s = c.createStatement();
          ResultSet rs =
              s.executeQuery(
                  "SELECT r.rolname FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner"
                      + " WHERE n.nspname = 'lease'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo(leaseLogin);
      }
    }
  }

  @Test
  void aServiceWithoutProvisionerCredentialsCreatesItsOwnSchemaWhenItMayCreateInTheDatabase()
      throws Exception {
    provisionDatabase(firmA);
    SharedPostgres.execute(dbOf(firmA), "GRANT CREATE ON DATABASE " + dbOf(firmA) + " TO " + leaseLogin);

    jdbc(pools(lease(null)), firmA)
        .update("INSERT INTO widget(id, label) VALUES (?, 'x')", UUID.randomUUID());

    try (Connection c = SharedPostgres.admin(dbOf(firmA))) {
      assertThat(count(c, "SELECT count(*) FROM lease.widget")).isEqualTo(1);
    }
  }

  // ------------------------------------------------------------- first use

  @Test
  void concurrentFirstUseOfAFirmMigratesExactlyOnce() throws Exception {
    provisionDatabase(firmA);
    TenancyProperties props = lease(SharedPostgres.ADMIN_PASSWORD);
    SpokeSchemaMigrator real = new SpokeSchemaMigrator(props);
    AtomicInteger migrations = new AtomicInteger();
    SpokePools p =
        new SpokePools(
            new TemplateSpokeLocator(props.databaseNameTemplate()),
            db -> {
              migrations.incrementAndGet();
              real.ensureReady(db);
            },
            props);
    opened.add(p);

    int threads = 16;
    ExecutorService exec = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      results.add(
          exec.submit(
              () -> {
                go.await();
                return new JdbcTemplate(p.forFirm(firmA))
                    .queryForObject("SELECT count(*) FROM widget", Integer.class);
              }));
    }
    go.countDown();
    for (Future<Integer> f : results) {
      assertThat(f.get()).isZero();
    }
    exec.shutdown();

    assertThat(migrations).hasValue(1);
    assertThat(p.openPoolCount()).isEqualTo(1);
    try (Connection c = SharedPostgres.admin(dbOf(firmA))) {
      assertThat(count(c, "SELECT count(*) FROM lease.databasechangelog")).isEqualTo(2);
    }
  }

  @Test
  void twoInstancesRacingOnTheSameFirmBothSucceedAndApplyEachChangesetOnce() throws Exception {
    provisionDatabase(firmA);
    TenancyProperties props = lease(SharedPostgres.ADMIN_PASSWORD);
    SpokePools first = pools(props);
    SpokePools second = pools(props);
    ExecutorService exec = Executors.newFixedThreadPool(2);
    CountDownLatch go = new CountDownLatch(1);

    Future<?> f1 = exec.submit(() -> { await(go); return first.forFirm(firmA); });
    Future<?> f2 = exec.submit(() -> { await(go); return second.forFirm(firmA); });
    go.countDown();
    f1.get();
    f2.get();
    exec.shutdown();

    try (Connection c = SharedPostgres.admin(dbOf(firmA))) {
      assertThat(count(c, "SELECT count(*) FROM lease.databasechangelog")).isEqualTo(2);
      assertThat(count(c, "SELECT count(DISTINCT id) FROM lease.databasechangelog")).isEqualTo(2);
    }
  }

  private static void await(CountDownLatch l) {
    try {
      l.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void differentFirmsFirstUsedAtTheSameTimeAreAllMigratedWithoutTrippingLiquibase() throws Exception {
    // Liquibase keeps process-wide scope state; several firms' first requests landing together
    // used to fail intermittently with "Cannot end scope". Six at once makes that reliable.
    List<TenantId> firms = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      TenantId firm = new TenantId(UUID.randomUUID());
      provisionDatabase(firm);
      firms.add(firm);
    }
    SpokePools p = pools(lease(SharedPostgres.ADMIN_PASSWORD));
    ExecutorService exec = Executors.newFixedThreadPool(firms.size());
    CountDownLatch go = new CountDownLatch(1);

    List<Future<?>> results = new ArrayList<>();
    for (TenantId firm : firms) {
      results.add(exec.submit(() -> { await(go); return p.forFirm(firm); }));
    }
    go.countDown();
    for (Future<?> f : results) {
      f.get();
    }
    exec.shutdown();

    for (TenantId firm : firms) {
      try (Connection c = SharedPostgres.admin(dbOf(firm))) {
        assertThat(count(c, "SELECT count(*) FROM lease.databasechangelog")).isEqualTo(2);
      }
    }
  }

  @Test
  void restartingAgainstAnAlreadyMigratedFirmChangesNothingAndKeepsTheData() throws Exception {
    provisionDatabase(firmA);
    TenancyProperties props = lease(SharedPostgres.ADMIN_PASSWORD);
    jdbc(pools(props), firmA).update("INSERT INTO widget(id, label) VALUES (?, 'kept')", UUID.randomUUID());

    SpokePools afterRestart = pools(props);

    assertThat(jdbc(afterRestart, firmA).queryForObject("SELECT label FROM widget", String.class))
        .isEqualTo("kept");
    try (Connection c = SharedPostgres.admin(dbOf(firmA))) {
      assertThat(count(c, "SELECT count(*) FROM lease.databasechangelog")).isEqualTo(2);
    }
  }

  // ------------------------------------------------------- cross-schema guard

  @Test
  void aLoginForOneServiceIsRefusedSelectOnAnotherServicesSchema() {
    provisionDatabase(firmA);
    SpokePools leasePools = pools(lease(SharedPostgres.ADMIN_PASSWORD));
    SpokePools acctPools = pools(accounting());
    jdbc(acctPools, firmA).update("INSERT INTO ledger_entry(id, amount) VALUES (?, 1)", UUID.randomUUID());
    JdbcTemplate asLease = jdbc(leasePools, firmA);
    JdbcTemplate asAcct = jdbc(acctPools, firmA);

    assertThatSqlState("42501", () -> asLease.queryForList("SELECT * FROM accounting.ledger_entry"));
    assertThatSqlState("42501", () -> asAcct.queryForList("SELECT * FROM lease.widget"));
  }

  @Test
  void aLoginForOneServiceCannotWriteOrCreateInAnotherServicesSchema() {
    provisionDatabase(firmA);
    SpokePools leasePools = pools(lease(SharedPostgres.ADMIN_PASSWORD));
    pools(accounting()).forFirm(firmA);
    JdbcTemplate asLease = jdbc(leasePools, firmA);

    assertThatSqlState(
        "42501",
        () -> asLease.update("INSERT INTO accounting.ledger_entry(id, amount) VALUES (?, 1)", UUID.randomUUID()));
    assertThatSqlState("42501", () -> asLease.execute("CREATE TABLE accounting.sneaky(id int)"));
  }

  private static void assertThatSqlState(String state, Runnable work) {
    assertThatExceptionOfType(org.springframework.dao.DataAccessException.class)
        .isThrownBy(work::run)
        .satisfies(
            e -> {
              Throwable t = e;
              while (t != null && !(t instanceof SQLException)) {
                t = t.getCause();
              }
              assertThat(t).as("an SQLException in the cause chain").isNotNull();
              assertThat(((SQLException) t).getSQLState()).isEqualTo(state);
            });
  }

  // ------------------------------------------------------------ fail closed

  @Test
  void aFirmWithNoDatabaseIsRefusedAndNothingIsCached() {
    SpokePools p = pools(lease(SharedPostgres.ADMIN_PASSWORD));

    assertThatExceptionOfType(SpokeUnavailableException.class).isThrownBy(() -> p.forFirm(firmB));
    assertThat(p.openPoolCount()).isZero();

    // Once the database exists the very next request succeeds -- the failure was not remembered.
    provisionDatabase(firmB);
    assertThat(jdbc(p, firmB).queryForObject("SELECT count(*) FROM widget", Integer.class)).isZero();
  }

  @Test
  void aWrongServicePasswordIsRefusedRatherThanFallingBackToAnotherLogin() {
    provisionDatabase(firmA);
    TenancyProperties props =
        new TenancyProperties(
            null, SharedPostgres.urlTemplate(), "asg_spoke_{tenant}", "lease", leaseLogin, "wrong",
            "db/changelog/test-lease.yaml", null, null, null, null, null);
    SpokePools p = pools(props);

    assertThatExceptionOfType(SpokeUnavailableException.class).isThrownBy(() -> p.forFirm(firmA));
  }

  @Test
  void aMissingChangelogFailsTheFirstUseLoudly() {
    provisionDatabase(firmA);
    TenancyProperties props =
        new TenancyProperties(
            null, SharedPostgres.urlTemplate(), "asg_spoke_{tenant}", "lease", leaseLogin, PW,
            "db/changelog/does-not-exist.yaml", SharedPostgres.ADMIN_USER,
            SharedPostgres.ADMIN_PASSWORD, null, null, null);
    SpokePools p = pools(props);

    assertThatExceptionOfType(SpokeUnavailableException.class)
        .isThrownBy(() -> p.forFirm(firmA))
        .havingRootCause()
        .withMessageContaining("does-not-exist");
  }

  @Test
  void aConnectionFromAPooledSpokeDefaultsToTheServiceSchemaNotPublic() throws Exception {
    provisionDatabase(firmA);
    SpokePools p = pools(lease(SharedPostgres.ADMIN_PASSWORD));

    try (Connection c = p.forFirm(firmA).getConnection()) {
      assertThat(c.getSchema()).isEqualTo("lease");
      try (Statement s = c.createStatement();
          ResultSet rs = s.executeQuery("SHOW search_path")) {
        rs.next();
        assertThat(rs.getString(1)).startsWith("lease");
      }
    }
  }

  @Test
  void poolsAreCappedAtTheConfiguredSize() throws Exception {
    provisionDatabase(firmA);
    TenancyProperties v = lease(SharedPostgres.ADMIN_PASSWORD);
    TenancyProperties props =
        new TenancyProperties(
            null, v.jdbcUrlTemplate(), v.databaseNameTemplate(), v.schema(), v.username(),
            v.password(), v.changelog(), v.adminUsername(), v.adminPassword(), 1,
            Duration.ofSeconds(30), null);
    SpokePools p = pools(props);
    com.zaxxer.hikari.HikariDataSource ds = (com.zaxxer.hikari.HikariDataSource) p.forFirm(firmA);
    ExecutorService exec = Executors.newSingleThreadExecutor();

    try (Connection held = ds.getConnection()) {
      Future<Connection> second = exec.submit(() -> ds.getConnection());
      var mx = ds.getHikariPoolMXBean();
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (mx.getThreadsAwaitingConnection() == 0 && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      // The second caller is queued behind the single permitted connection, not given a second one.
      assertThat(mx.getThreadsAwaitingConnection()).isEqualTo(1);
      assertThat(mx.getTotalConnections()).isEqualTo(1);
      held.close();
      second.get(10, java.util.concurrent.TimeUnit.SECONDS).close();
    } finally {
      exec.shutdownNow();
    }
  }

  // ------------------------------------------------- review fixes (PR #1)

  @Test
  void aTransactionInOneFirmCannotWriteToAnotherFirmBySwitchingScope() {
    provisionDatabase(firmA);
    provisionDatabase(firmB);
    SpokePools p = pools(lease(SharedPostgres.ADMIN_PASSWORD));
    TenantRoutingDataSource routing = new TenantRoutingDataSource(p, null);
    JdbcTemplate routed = new JdbcTemplate(routing);
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(routing));

    assertThatThrownBy(
            () ->
                TenantScopes.runIn(
                    TenantScope.firm(firmA),
                    () ->
                        tx.executeWithoutResult(
                            status ->
                                TenantScopes.runIn(
                                    TenantScope.firm(firmB),
                                    () ->
                                        routed.update(
                                            "INSERT INTO widget(id, label) VALUES (?, 'meant-for-b')",
                                            UUID.randomUUID())))))
        .isInstanceOf(IllegalStateException.class);

    assertThat(jdbc(p, firmA).queryForObject("SELECT count(*) FROM widget", Integer.class)).isZero();
    assertThat(jdbc(p, firmB).queryForObject("SELECT count(*) FROM widget", Integer.class)).isZero();
  }

  @Test
  void aChangelogLockLeftByACrashedInstanceTimesOutThatFirmAloneAndOtherFirmsStillOpen()
      throws Exception {
    provisionDatabase(firmA);
    provisionDatabase(firmB);
    TenancyProperties props = lease(SharedPostgres.ADMIN_PASSWORD);
    jdbc(pools(props), firmA).queryForObject("SELECT count(*) FROM widget", Integer.class);
    SharedPostgres.execute(
        dbOf(firmA),
        "UPDATE lease.databasechangeloglock SET locked = true, lockgranted = now(), lockedby = 'crashed-pod'");

    Duration timeout = Duration.ofSeconds(6);
    SpokePools stuck =
        new SpokePools(
            new TemplateSpokeLocator(props.databaseNameTemplate()),
            new SpokeSchemaMigrator(props, timeout),
            props);
    opened.add(stuck);
    long started = System.nanoTime();
    CompletableFuture<Object> firmAFirstUse = CompletableFuture.supplyAsync(() -> stuck.forFirm(firmA));
    Thread.sleep(300);

    SpokePools other =
        new SpokePools(
            new TemplateSpokeLocator(props.databaseNameTemplate()),
            new SpokeSchemaMigrator(props, timeout),
            props);
    opened.add(other);
    assertThat(jdbc(other, firmB).queryForObject("SELECT count(*) FROM widget", Integer.class)).isZero();
    assertThat(firmAFirstUse).as("firm B opened while firm A was still waiting").isNotDone();

    assertThatThrownBy(firmAFirstUse::join).hasRootCauseMessage(
        "the changelog lock of schema 'lease' has been held for longer than PT6S; a crashed migration may have left DATABASECHANGELOGLOCK set");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
  }
}
