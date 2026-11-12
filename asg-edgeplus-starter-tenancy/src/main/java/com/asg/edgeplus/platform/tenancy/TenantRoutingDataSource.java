package com.asg.edgeplus.platform.tenancy;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * Hands out a connection to the spoke of whichever firm {@link TenantScopes} says the current work
 * is for.
 *
 * <p>Not Spring's {@code AbstractRoutingDataSource}: that resolves against a fixed map of targets
 * declared at startup, while firms (and their spokes) appear at runtime. Resolving on each call
 * means a firm provisioned a minute ago is routable at once, with no bean refresh.
 *
 * <p><b>Fails closed.</b> No scope bound is an error, never a default database — unlike a
 * hub-by-default design, a forgotten tenant here is an exception rather than someone else's data.
 */
public class TenantRoutingDataSource extends AbstractDataSource {

  private final SpokePools pools;
  private final DataSource platform;

  /**
   * @param platform serves platform scope; {@code null} when the service has no platform database,
   *     in which case platform scope is refused
   */
  public TenantRoutingDataSource(SpokePools pools, DataSource platform) {
    this.pools = pools;
    this.platform = platform;
  }

  private DataSource resolve() {
    TenantScope scope = TenantScopes.current().orElseThrow(MissingTenantScopeException::new);
    return switch (scope) {
      case TenantScope.Firm firm -> pools.forFirm(firm.tenantId());
      case TenantScope.Platform p -> {
        if (platform == null) {
          throw new PlatformScopeUnavailableException();
        }
        yield platform;
      }
    };
  }

  @Override
  public Connection getConnection() throws SQLException {
    return resolve().getConnection();
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return resolve().getConnection(username, password);
  }
}
