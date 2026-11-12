package com.asg.edgeplus.platform.tenancy;

import com.asg.edgeplus.platform.domain.primitives.TenantId;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One small connection pool per firm, created on the firm's first request and cached.
 *
 * <p><b>Pool size is the constraint that bites at this estate's scale.</b> A full-sized pool per
 * firm passes Postgres' default {@code max_connections} of 100 at around twenty firms; ASG is
 * already there. So pools are capped small, hold no idle connection ({@code minimumIdle=0}) and
 * open no connection when constructed — an idle firm costs nothing. Past roughly fifty firms this
 * wants PgBouncer or RDS Proxy rather than a larger number.
 *
 * <p><b>First use.</b> Before a firm's pool exists, its schema is created and its changelog run
 * ({@link SpokeProvisioner}) — once per firm per JVM, under a per-firm lock, so concurrent first
 * requests for one firm wait for a single migration while other firms proceed. A failure is not
 * cached: the request fails, the next one retries.
 */
public class SpokePools implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SpokePools.class);

  private final SpokeLocator locator;
  private final SpokeProvisioner provisioner;
  private final TenancyProperties properties;
  private final Map<TenantId, HikariDataSource> pools = new ConcurrentHashMap<>();
  private final Map<TenantId, Object> locks = new ConcurrentHashMap<>();
  private volatile boolean closed;

  public SpokePools(
      SpokeLocator locator, SpokeProvisioner provisioner, TenancyProperties properties) {
    this.locator = locator;
    this.provisioner = provisioner;
    this.properties = properties;
  }

  /**
   * The pool for one firm, provisioning its schema first if this is the first use.
   *
   * @throws SpokeUnavailableException if the firm's spoke cannot be opened. Never falls back to a
   *     different database.
   */
  public DataSource forFirm(TenantId tenant) {
    HikariDataSource cached = pools.get(tenant);
    if (cached != null) {
      return cached;
    }
    // A lock per firm, taken OUTSIDE ConcurrentHashMap.computeIfAbsent: provisioning is slow and
    // does I/O, and holding a map bin lock across it would stall every other firm hashing there.
    synchronized (locks.computeIfAbsent(tenant, t -> new Object())) {
      cached = pools.get(tenant);
      if (cached != null) {
        return cached;
      }
      return open(tenant);
    }
  }

  private DataSource open(TenantId tenant) {
    if (closed) {
      throw new SpokeUnavailableException(
          tenant, "tenancy is shut down; refusing to open a pool for firm " + tenant.value(), null);
    }
    HikariDataSource pool = null;
    try {
      String database = locator.databaseName(tenant);
      String url = properties.jdbcUrl(database); // validates the name before any SQL sees it
      provisioner.ensureReady(database);
      pool = newPool(database, url);
      pools.put(tenant, pool);
      if (closed) { // close() raced with us after the check above
        pools.remove(tenant);
        pool.close();
        throw new SpokeUnavailableException(
            tenant, "tenancy shut down while opening firm " + tenant.value(), null);
      }
      return pool;
    } catch (SpokeUnavailableException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new SpokeUnavailableException(
          tenant, "could not open the spoke for firm " + tenant.value(), e);
    }
  }

  private HikariDataSource newPool(String database, String url) {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(url);
    cfg.setUsername(properties.username());
    cfg.setPassword(properties.password());
    cfg.setSchema(properties.schema());
    cfg.setMaximumPoolSize(properties.poolMaxSize());
    cfg.setMinimumIdle(0);
    cfg.setIdleTimeout(properties.poolIdleTimeout().toMillis());
    cfg.setPoolName("spoke-" + properties.schema() + "-" + database);
    // Do not connect while constructing the pool: Hikari's default is to validate eagerly and
    // throw. Problems surface on getConnection(), where they belong.
    cfg.setInitializationFailTimeout(-1);
    log.info(
        "tenancy opened pool for {} (schema {}, max {} connections)",
        database,
        properties.schema(),
        properties.poolMaxSize());
    return new HikariDataSource(cfg);
  }

  /** Drops and closes one firm's pool, e.g. when its spoke is suspended or re-provisioned. */
  public void evict(TenantId tenant) {
    synchronized (locks.computeIfAbsent(tenant, t -> new Object())) {
      HikariDataSource ds = pools.remove(tenant);
      if (ds != null) {
        ds.close();
        log.info("tenancy evicted pool for firm {}", tenant.value());
      }
    }
  }

  public int openPoolCount() {
    return pools.size();
  }

  @Override
  public void close() {
    closed = true;
    pools.values().forEach(HikariDataSource::close);
    pools.clear();
  }
}
