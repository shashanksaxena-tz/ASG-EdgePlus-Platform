package com.asg.edgeplus.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.asg.edgeplus.platform.domain.primitives.TenantId;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Pool behaviour against a provisioner that never touches a database. Pools point at a closed
 * port: if one connected eagerly, these tests would fail, which is exactly the property under test.
 */
class SpokePoolsTest {

  private static final TenantId A = TenantId.STANDARD;
  private static final TenantId B = TenantId.of("00000000-0000-0000-0000-0000000000b2");

  private final List<String> provisioned = new ArrayList<>();
  private final AtomicInteger provisionCalls = new AtomicInteger();
  private SpokePools pools;

  private SpokePools pools(TenancyProperties props, SpokeProvisioner provisioner) {
    pools =
        new SpokePools(
            new TemplateSpokeLocator(props.databaseNameTemplate()), provisioner, props);
    return pools;
  }

  private SpokePools pools(TenancyProperties props) {
    return pools(
        props,
        db -> {
          synchronized (provisioned) {
            provisioned.add(db);
          }
          provisionCalls.incrementAndGet();
        });
  }

  @AfterEach
  void closePools() {
    if (pools != null) {
      pools.close();
    }
  }

  @Test
  void opensNothingUntilAFirmIsFirstUsed() {
    SpokePools p = pools(TestProperties.withUrlTemplate("jdbc:postgresql://127.0.0.1:1/{db}"));

    assertThat(p.openPoolCount()).isZero();
    assertThat(provisionCalls).hasValue(0);
  }

  @Test
  void provisionsTheFirmsDatabaseAndCachesThePool() {
    SpokePools p = pools(TestProperties.withUrlTemplate("jdbc:postgresql://127.0.0.1:1/{db}"));

    DataSource first = p.forFirm(A);
    DataSource second = p.forFirm(A);

    assertThat(second).isSameAs(first);
    assertThat(p.openPoolCount()).isEqualTo(1);
    assertThat(provisioned).containsExactly("asg_spoke_00000000000000000000000000000001");
  }

  @Test
  void givesEachFirmItsOwnPoolAndProvisionsEach() {
    SpokePools p = pools(TestProperties.withUrlTemplate("jdbc:postgresql://127.0.0.1:1/{db}"));

    assertThat(p.forFirm(A)).isNotSameAs(p.forFirm(B));
    assertThat(p.openPoolCount()).isEqualTo(2);
    assertThat(provisionCalls).hasValue(2);
  }

  @Test
  void poolsAreSmallLazyAndPointAtTheServiceSchema() {
    SpokePools p =
        pools(
            new TenancyProperties(
                null,
                "jdbc:postgresql://127.0.0.1:1/{db}",
                "asg_spoke_{tenant}",
                "lease",
                "svc_lease",
                "test-only-pw",
                "db/changelog/test-lease.yaml",
                null,
                null,
                4,
                Duration.ofMinutes(2),
                null));

    HikariDataSource ds = (HikariDataSource) p.forFirm(A);

    assertThat(ds.getMaximumPoolSize()).isEqualTo(4);
    assertThat(ds.getMinimumIdle()).isZero();
    assertThat(ds.getIdleTimeout()).isEqualTo(Duration.ofMinutes(2).toMillis());
    assertThat(ds.getSchema()).isEqualTo("lease");
    assertThat(ds.getUsername()).isEqualTo("svc_lease");
    assertThat(ds.getPassword()).isEqualTo("test-only-pw");
    assertThat(ds.getJdbcUrl())
        .isEqualTo("jdbc:postgresql://127.0.0.1:1/asg_spoke_00000000000000000000000000000001");
    assertThat(ds.getPoolName()).startsWith("spoke-lease-").contains("asg_spoke_");
    assertThat(ds.getInitializationFailTimeout()).isEqualTo(-1);
  }

  @Test
  void theDefaultPoolIsSmallEnoughForTwentyFirmsAgainstPostgresDefaults() {
    SpokePools p = pools(TestProperties.withUrlTemplate("jdbc:postgresql://127.0.0.1:1/{db}"));

    assertThat(((HikariDataSource) p.forFirm(A)).getMaximumPoolSize()).isEqualTo(3);
  }

  @Test
  void concurrentFirstUseOfOneFirmProvisionsOnceAndSharesOnePool() throws Exception {
    int threads = 24;
    CountDownLatch inside = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    SpokePools p =
        pools(
            TestProperties.withUrlTemplate("jdbc:postgresql://127.0.0.1:1/{db}"),
            db -> {
              calls.incrementAndGet();
              try {
                // Hold the first caller inside provisioning so the others pile up behind it.
                inside.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    ExecutorService exec = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<DataSource>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      results.add(
          exec.submit(
              () -> {
                start.await();
                return p.forFirm(A);
              }));
    }
    start.countDown();
    List<DataSource> seen = new ArrayList<>();
    for (Future<DataSource> f : results) {
      seen.add(f.get());
    }
    exec.shutdownNow();

    assertThat(calls).hasValue(1);
    assertThat(seen).doesNotContainNull().containsOnly(seen.get(0));
    assertThat(p.openPoolCount()).isEqualTo(1);
  }

  @Test
  void differentFirmsDoNotWaitOnEachOther() throws Exception {
    CountDownLatch aInside = new CountDownLatch(1);
    CountDownLatch releaseA = new CountDownLatch(1);
    SpokePools p =
        pools(
            TestProperties.withUrlTemplate("jdbc:postgresql://127.0.0.1:1/{db}"),
            db -> {
              if (db.endsWith("0001")) {
                aInside.countDown();
                try {
                  releaseA.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    ExecutorService exec = Executors.newFixedThreadPool(2);
    Future<DataSource> a = exec.submit(() -> p.forFirm(A));
    assertThat(aInside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    DataSource b = exec.submit(() -> p.forFirm(B)).get(2, java.util.concurrent.TimeUnit.SECONDS);

    assertThat(b).isNotNull();
    assertThat(a.isDone()).isFalse();
    releaseA.countDown();
    assertThat(a.get()).isNotNull();
    exec.shutdownNow();
  }

  @Test
  void aFailedProvisioningIsNotCachedSoTheNextRequestRetries() {
    AtomicInteger attempts = new AtomicInteger();
    SpokePools p =
        pools(
            TestProperties.withUrlTemplate("jdbc:postgresql://127.0.0.1:1/{db}"),
            db -> {
              if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("database is down");
              }
            });

    assertThatExceptionOfType(SpokeUnavailableException.class)
        .isThrownBy(() -> p.forFirm(A))
        .withMessageContaining(A.value().toString())
        .withCauseInstanceOf(IllegalStateException.class);
    assertThat(p.openPoolCount()).isZero();

    assertThat(p.forFirm(A)).isNotNull();
    assertThat(attempts).hasValue(2);
  }

  @Test
  void anUnsafeDatabaseNameFromTheLocatorIsRefusedBeforeAnythingIsProvisioned() {
    SpokePools p =
        new SpokePools(
            tenant -> "postgres",
            db -> provisionCalls.incrementAndGet(),
            TestProperties.withUrlTemplate("jdbc:postgresql://127.0.0.1:1/{db}"));
    pools = p;

    assertThatExceptionOfType(SpokeUnavailableException.class).isThrownBy(() -> p.forFirm(A));
    assertThat(provisionCalls).hasValue(0);
    assertThat(p.openPoolCount()).isZero();
  }

  @Test
  void evictClosesAndForgetsOnePool() {
    SpokePools p = pools(TestProperties.withUrlTemplate("jdbc:postgresql://127.0.0.1:1/{db}"));
    HikariDataSource a = (HikariDataSource) p.forFirm(A);
    p.forFirm(B);

    p.evict(A);

    assertThat(a.isClosed()).isTrue();
    assertThat(p.openPoolCount()).isEqualTo(1);
    p.evict(A); // evicting something absent is a no-op
    assertThat(p.openPoolCount()).isEqualTo(1);
  }

  @Test
  void aFailureCarriesTheFirmItWasFor() {
    SpokePools p =
        pools(
            TestProperties.withUrlTemplate("jdbc:postgresql://127.0.0.1:1/{db}"),
            db -> {
              throw new IllegalStateException("down");
            });

    assertThatExceptionOfType(SpokeUnavailableException.class)
        .isThrownBy(() -> p.forFirm(B))
        .satisfies(e -> assertThat(e.tenantId()).isEqualTo(B));
  }

  @Test
  void closeShutsEveryPool() {
    SpokePools p = pools(TestProperties.withUrlTemplate("jdbc:postgresql://127.0.0.1:1/{db}"));
    HikariDataSource a = (HikariDataSource) p.forFirm(A);
    HikariDataSource b = (HikariDataSource) p.forFirm(B);

    p.close();

    assertThat(a.isClosed()).isTrue();
    assertThat(b.isClosed()).isTrue();
    assertThat(p.openPoolCount()).isZero();
  }

  @Test
  void aClosedRegistryRefusesNewPoolsInsteadOfLeakingThem() {
    SpokePools p = pools(TestProperties.withUrlTemplate("jdbc:postgresql://127.0.0.1:1/{db}"));
    p.close();

    assertThatExceptionOfType(SpokeUnavailableException.class).isThrownBy(() -> p.forFirm(A));
    assertThat(p.openPoolCount()).isZero();
  }
}
