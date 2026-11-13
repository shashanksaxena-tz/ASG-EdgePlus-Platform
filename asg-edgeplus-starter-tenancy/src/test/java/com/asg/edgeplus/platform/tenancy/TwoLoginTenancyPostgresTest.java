package com.asg.edgeplus.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.asg.edgeplus.platform.domain.primitives.TenantId;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The two-login mode (ADR-0058 amendment 2026-11-13, AE-Q-006 B): a migration login owns the
 * service schema and runs its changelog; requests run as a runtime login that is not the owner, so
 * the grants the changelog gives it are the whole of what it can do.
 */
@EnabledIfSystemProperty(named = "docker.available", matches = "true")
class TwoLoginTenancyPostgresTest {

  private static final String PW = "svc-test-only";

  private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  private final TenantId firm = new TenantId(UUID.randomUUID());

  private String migrator;
  private String runtime;
  private SpokePools pools;

  @BeforeEach
  void roles() {
    migrator = "svc_ledger_mig_" + suffix;
    runtime = "svc_ledger_rt_" + suffix;
    SharedPostgres.createLogin(migrator, PW);
    SharedPostgres.createLogin(runtime, PW);
    SharedPostgres.createDatabase(db());
  }

  @AfterEach
  void close() {
    if (pools != null) {
      pools.close();
    }
  }

  private String db() {
    return new TemplateSpokeLocator("asg_spoke_{tenant}").databaseName(firm);
  }

  private TenancyProperties props(boolean withAdmin) {
    return new TenancyProperties(
        null, SharedPostgres.urlTemplate(), "asg_spoke_{tenant}", "ledger", runtime, PW,
        "db/changelog/test-ledger-two-logins.yaml",
        withAdmin ? SharedPostgres.ADMIN_USER : null,
        withAdmin ? SharedPostgres.ADMIN_PASSWORD : null,
        null, null, null, migrator, PW);
  }

  private JdbcTemplate runtimeJdbc(TenancyProperties p) {
    pools = new SpokePools(new TemplateSpokeLocator(p.databaseNameTemplate()), new SpokeSchemaMigrator(p), p);
    return new JdbcTemplate(pools.forFirm(firm));
  }

  private String scalar(String sql) throws SQLException {
    try (Connection c = SharedPostgres.admin(db());
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }

  @Test
  void theMigrationLoginOwnsTheSchemaItsTablesAndItsChangelogHistory() throws Exception {
    runtimeJdbc(props(true)).queryForObject("SELECT count(*) FROM ledger_row", Integer.class);

    assertThat(scalar("SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = 'ledger'"))
        .isEqualTo(migrator);
    assertThat(scalar("SELECT tableowner FROM pg_tables WHERE schemaname = 'ledger' AND tablename = 'ledger_row'"))
        .isEqualTo(migrator);
    assertThat(scalar("SELECT tableowner FROM pg_tables WHERE schemaname = 'ledger' AND tablename = 'databasechangelog'"))
        .isEqualTo(migrator);
  }

  @Test
  void requestsRunAsTheRuntimeLoginWithOnlyTheGrantsTheChangelogGave() {
    JdbcTemplate jdbc = runtimeJdbc(props(true));

    assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(runtime);
    jdbc.update("INSERT INTO ledger_row(id, amount) VALUES (?, 1.0000)", UUID.randomUUID());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_row", Integer.class)).isEqualTo(1);

    assertInsufficientPrivilege(() -> jdbc.update("UPDATE ledger_row SET amount = 2"));
    assertInsufficientPrivilege(() -> jdbc.update("DELETE FROM ledger_row"));
    assertInsufficientPrivilege(() -> jdbc.execute("TRUNCATE ledger_row"));
  }

  @Test
  void theRuntimeLoginCannotAlterDropOrCreateBecauseItIsNotTheOwner() {
    JdbcTemplate jdbc = runtimeJdbc(props(true));

    assertInsufficientPrivilege(() -> jdbc.execute("ALTER TABLE ledger_row ADD COLUMN x int"));
    assertInsufficientPrivilege(() -> jdbc.execute("DROP TABLE ledger_row"));
    assertInsufficientPrivilege(() -> jdbc.execute("CREATE TABLE ledger.sneaky (id int)"));
  }

  @Test
  void withoutAProvisionerTheMigrationLoginCreatesAndOwnsTheSchema() throws Exception {
    SharedPostgres.execute(db(), "GRANT CREATE ON DATABASE " + db() + " TO " + migrator);

    runtimeJdbc(props(false)).queryForObject("SELECT count(*) FROM ledger_row", Integer.class);

    assertThat(scalar("SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = 'ledger'"))
        .isEqualTo(migrator);
  }

  @Test
  void reopeningAnAlreadyMigratedFirmKeepsOwnershipAndData() {
    JdbcTemplate first = runtimeJdbc(props(true));
    first.update("INSERT INTO ledger_row(id, amount) VALUES (?, 1.0000)", UUID.randomUUID());
    pools.close();

    JdbcTemplate second = runtimeJdbc(props(true));
    assertThat(second.queryForObject("SELECT count(*) FROM ledger_row", Integer.class)).isEqualTo(1);
  }

  // ------------------------------------------------- review fixes (PR #2)

  @Test
  void twoInstancesFirstUsingOneFirmTogetherBothSucceedAndGrantOnce() {
    TenancyProperties p = props(true);
    List<SpokePools> instances = new ArrayList<>();
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<CompletableFuture<Integer>> firstUses = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        SpokePools instance =
            new SpokePools(new TemplateSpokeLocator(p.databaseNameTemplate()), new SpokeSchemaMigrator(p), p);
        instances.add(instance);
        firstUses.add(
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    go.await();
                  } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                  }
                  return new JdbcTemplate(instance.forFirm(firm))
                      .queryForObject("SELECT count(*) FROM ledger_row", Integer.class);
                }));
      }
      go.countDown();
      firstUses.forEach(f -> assertThat(f.join()).isZero());
    } finally {
      instances.forEach(SpokePools::close);
    }
  }

  @Test
  void aSchemaAlreadyOwnedByTheRuntimeLoginIsRefusedNotMigrated() {
    SharedPostgres.execute(db(), "CREATE SCHEMA ledger AUTHORIZATION " + runtime);

    assertThatThrownBy(() -> runtimeJdbc(props(true)))
        .isInstanceOf(SpokeUnavailableException.class)
        .hasStackTraceContaining("not by the migration login");
  }

  @Test
  void aRuntimeLoginThatIsAMemberOfTheOwnerRoleIsRefused() {
    runtimeJdbc(props(true));
    pools.close();
    SharedPostgres.execute(db(), "GRANT " + migrator + " TO " + runtime);

    assertThatThrownBy(() -> runtimeJdbc(props(true)))
        .isInstanceOf(SpokeUnavailableException.class)
        .hasStackTraceContaining("member of the owner role");
  }

  @Test
  void theRuntimeLoginCannotReadOrWriteTheChangelogHistory() {
    JdbcTemplate jdbc = runtimeJdbc(props(true));

    assertInsufficientPrivilege(() -> jdbc.queryForObject("SELECT count(*) FROM databasechangelog", Integer.class));
    assertInsufficientPrivilege(() -> jdbc.update("DELETE FROM databasechangeloglock"));
  }

  @Test
  void inSingleLoginModeTheRuntimeLoginParameterNamesTheServiceLoginItself() throws Exception {
    TenancyProperties single =
        new TenancyProperties(
            null, SharedPostgres.urlTemplate(), "asg_spoke_{tenant}", "ledger", runtime, PW,
            "db/changelog/test-ledger-two-logins.yaml", SharedPostgres.ADMIN_USER,
            SharedPostgres.ADMIN_PASSWORD, null, null, null, null, null);

    JdbcTemplate jdbc = runtimeJdbc(single);

    jdbc.update("INSERT INTO ledger_row(id, amount) VALUES (?, 1.0000)", UUID.randomUUID());
    assertThat(scalar("SELECT tableowner FROM pg_tables WHERE schemaname = 'ledger' AND tablename = 'ledger_row'"))
        .isEqualTo(runtime);
  }

  private static void assertInsufficientPrivilege(Runnable work) {
    assertThatThrownBy(work::run)
        .satisfies(
            e -> {
              Throwable t = e;
              while (t != null && !(t instanceof SQLException)) {
                t = t.getCause();
              }
              assertThat(t).as("an SQLException in the cause chain").isNotNull();
              assertThat(((SQLException) t).getSQLState()).isEqualTo("42501");
            });
  }
}
