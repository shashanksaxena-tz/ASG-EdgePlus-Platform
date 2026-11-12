package com.asg.edgeplus.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.asg.edgeplus.platform.domain.primitives.TenantId;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class TenantRoutingDataSourceTest {

  private static final TenantId FIRM = TenantId.STANDARD;

  private final SpokePools pools = mock(SpokePools.class);
  private final DataSource spoke = mock(DataSource.class);
  private final DataSource platform = mock(DataSource.class);
  private final Connection spokeConnection = mock(Connection.class);
  private final Connection platformConnection = mock(Connection.class);

  private TenantRoutingDataSource router(DataSource platformOrNull) throws SQLException {
    when(pools.forFirm(FIRM)).thenReturn(spoke);
    when(spoke.getConnection()).thenReturn(spokeConnection);
    when(spoke.getConnection("u", "p")).thenReturn(spokeConnection);
    if (platformOrNull != null) {
      when(platformOrNull.getConnection()).thenReturn(platformConnection);
      when(platformOrNull.getConnection("u", "p")).thenReturn(platformConnection);
    }
    return new TenantRoutingDataSource(pools, platformOrNull);
  }

  @Test
  void handsOutAConnectionToTheBoundFirmsSpoke() throws Exception {
    TenantRoutingDataSource ds = router(null);

    Connection c = TenantScopes.callIn(TenantScope.firm(FIRM), () -> unchecked(ds::getConnection));

    assertThat(c).isSameAs(spokeConnection);
  }

  @Test
  void routesCredentialedConnectionRequestsTheSameWay() throws Exception {
    TenantRoutingDataSource ds = router(null);

    Connection c =
        TenantScopes.callIn(TenantScope.firm(FIRM), () -> unchecked(() -> ds.getConnection("u", "p")));

    assertThat(c).isSameAs(spokeConnection);
  }

  @Test
  void failsClosedWhenNoScopeIsBoundInsteadOfPickingADatabase() throws Exception {
    TenantRoutingDataSource ds = router(platform);

    assertThatExceptionOfType(MissingTenantScopeException.class)
        .isThrownBy(ds::getConnection)
        .withMessageContaining("spring.liquibase.enabled=false");
    assertThatExceptionOfType(MissingTenantScopeException.class)
        .isThrownBy(() -> ds.getConnection("u", "p"));
    verifyNoInteractions(platform);
    verify(pools, org.mockito.Mockito.never()).forFirm(any());
  }

  @Test
  void platformScopeUsesThePlatformDataSourceWhenOneIsConfigured() throws Exception {
    TenantRoutingDataSource ds = router(platform);

    Connection c = TenantScopes.callIn(TenantScope.platform(), () -> unchecked(ds::getConnection));
    Connection c2 =
        TenantScopes.callIn(
            TenantScope.platform(), () -> unchecked(() -> ds.getConnection("u", "p")));

    assertThat(c).isSameAs(platformConnection);
    assertThat(c2).isSameAs(platformConnection);
    verifyNoInteractions(spoke);
  }

  @Test
  void platformScopeWithoutAPlatformDataSourceIsRefusedNotServedFromASpoke() throws Exception {
    TenantRoutingDataSource ds = router(null);

    TenantScopes.runIn(
        TenantScope.platform(),
        () -> {
          assertThatExceptionOfType(PlatformScopeUnavailableException.class)
              .isThrownBy(ds::getConnection);
          assertThatExceptionOfType(PlatformScopeUnavailableException.class)
              .isThrownBy(() -> ds.getConnection("u", "p"));
        });
    verifyNoInteractions(spoke);
  }

  private interface SqlSupplier {
    Connection get() throws SQLException;
  }

  private static Connection unchecked(SqlSupplier s) {
    try {
      return s.get();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
