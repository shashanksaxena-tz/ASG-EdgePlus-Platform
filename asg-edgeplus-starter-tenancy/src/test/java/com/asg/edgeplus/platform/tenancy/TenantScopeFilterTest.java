package com.asg.edgeplus.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.asg.edgeplus.platform.domain.primitives.TenantId;
import com.asg.edgeplus.platform.identity.GatewayIdentityFilter;
import com.asg.edgeplus.platform.identity.GatewayIdentityProperties;
import jakarta.servlet.FilterChain;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Runs the real identity filter first, exactly as the servlet container orders them, so these tests
 * cover the hand-off between the two starters and not a stubbed CallerContext.
 */
class TenantScopeFilterTest {

  private static final String USER = "7d0c9b1e-1f55-4b53-9c1a-2a4a9f2f0001";
  private static final String FIRM = "11111111-2222-3333-4444-555555555555";

  private final GatewayIdentityFilter identity =
      new GatewayIdentityFilter(new GatewayIdentityProperties(null, null, null));
  private final List<Optional<TenantScope>> seenInChain = new ArrayList<>();

  private TenantScopeFilter filter(String... platformRoles) {
    return new TenantScopeFilter(
        TestProperties.withPlatformRoles(List.of(platformRoles)),
        new GatewayIdentityProperties(null, null, null));
  }

  private MockHttpServletRequest request(String path, String tenant, String roles, String scope) {
    MockHttpServletRequest r = new MockHttpServletRequest("GET", path);
    r.setRequestURI(path);
    r.addHeader("X-User-Id", USER);
    if (tenant != null) {
      r.addHeader("X-Tenant-Id", tenant);
    }
    if (roles != null) {
      r.addHeader("X-Roles", roles);
    }
    if (scope != null) {
      r.addHeader("X-Tenant-Scope", scope);
    }
    return r;
  }

  private MockHttpServletResponse run(TenantScopeFilter scopeFilter, MockHttpServletRequest req)
      throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    FilterChain terminal = (rq, rs) -> seenInChain.add(TenantScopes.current());
    identity.doFilter(req, res, (rq, rs) -> scopeFilter.doFilter(rq, rs, terminal));
    return res;
  }

  @Test
  void routesToTheFirmTheGatewayNamed() throws Exception {
    MockHttpServletResponse res = run(filter(), request("/v1/leases", FIRM, "USER", null));

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(seenInChain).containsExactly(Optional.of(TenantScope.firm(TenantId.of(FIRM))));
  }

  @Test
  void releasesTheScopeAfterTheRequest() throws Exception {
    run(filter(), request("/v1/leases", FIRM, null, null));
    assertThat(TenantScopes.current()).isEmpty();
  }

  @Test
  void releasesTheScopeWhenTheRequestFails() {
    FilterChain failing =
        (rq, rs) -> {
          throw new IllegalStateException("boom");
        };
    TenantScopeFilter f = filter();
    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                identity.doFilter(
                    request("/v1/leases", FIRM, null, null),
                    new MockHttpServletResponse(),
                    (rq, rs) -> f.doFilter(rq, rs, failing)));
    assertThat(TenantScopes.current()).isEmpty();
  }

  @Test
  void aCallerWithNoTenantAndNoExplicitScopeGetsNoScopeAtAll() throws Exception {
    run(filter("SYSADMIN"), request("/v1/leases", null, "SYSADMIN", null));

    // Platform scope is never inferred from a missing tenant, even for a caller who may use it.
    assertThat(seenInChain).containsExactly(Optional.empty());
  }

  @Test
  void aLiteralNullTenantHeaderIsTreatedAsNoTenant() throws Exception {
    run(filter(), request("/v1/leases", "null", null, null));
    assertThat(seenInChain).containsExactly(Optional.empty());
  }

  @Test
  void platformScopeIsGrantedOnlyWhenAskedForByHeaderAndTheCallerHoldsAPlatformRole()
      throws Exception {
    MockHttpServletResponse res =
        run(filter("SYSADMIN"), request("/v1/firms", null, "SYSADMIN,USER", "platform"));

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(seenInChain).containsExactly(Optional.of(TenantScope.platform()));
  }

  @Test
  void theScopeHeaderIsCaseInsensitive() throws Exception {
    run(filter("SYSADMIN"), request("/v1/firms", null, "SYSADMIN", "PLATFORM"));
    assertThat(seenInChain).containsExactly(Optional.of(TenantScope.platform()));
  }

  @Test
  void platformScopeWinsOverTheCallersTenantWhenExplicitlyRequested() throws Exception {
    run(filter("SYSADMIN"), request("/v1/firms", FIRM, "SYSADMIN", "platform"));
    assertThat(seenInChain).containsExactly(Optional.of(TenantScope.platform()));
  }

  @Test
  void aCallerWithoutAPlatformRoleIsRefusedPlatformScopeAndTheChainNeverRuns() throws Exception {
    MockHttpServletResponse res =
        run(filter("SYSADMIN"), request("/v1/firms", FIRM, "USER", "platform"));

    assertThat(res.getStatus()).isEqualTo(403);
    assertThat(seenInChain).isEmpty();
    assertThat(TenantScopes.current()).isEmpty();
  }

  @Test
  void platformScopeIsRefusedWhenNoPlatformRoleIsConfiguredAtAll() throws Exception {
    MockHttpServletResponse res =
        run(filter(), request("/v1/firms", null, "SYSADMIN", "platform"));

    assertThat(res.getStatus()).isEqualTo(403);
    assertThat(seenInChain).isEmpty();
  }

  @Test
  void anyOtherScopeValueIsABadRequestRatherThanSilentlyFallingBackToTheFirm() throws Exception {
    for (String bogus : List.of("hub", "firm", "global", "")) {
      seenInChain.clear();
      MockHttpServletResponse res =
          run(filter("SYSADMIN"), request("/v1/leases", FIRM, "SYSADMIN", bogus));
      assertThat(res.getStatus()).as("scope '%s'", bogus).isEqualTo(400);
      assertThat(seenInChain).isEmpty();
    }
  }

  @Test
  void permitAllPathsPassThroughUnscopedEvenWithAScopeHeader() throws Exception {
    MockHttpServletRequest health = request("/actuator/health", null, null, "platform");
    MockHttpServletResponse res = run(filter(), health);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(seenInChain).containsExactly(Optional.empty());
  }

  @Test
  void aRequestWithNoIdentityAtAllIsLeftUnscoped() throws Exception {
    MockHttpServletRequest anonymous = new MockHttpServletRequest("GET", "/actuator/health");
    anonymous.setRequestURI("/actuator/health");
    MockHttpServletResponse res = new MockHttpServletResponse();
    FilterChain terminal = (rq, rs) -> seenInChain.add(TenantScopes.current());

    identity.doFilter(anonymous, res, (rq, rs) -> filter().doFilter(rq, rs, terminal));

    assertThat(seenInChain).containsExactly(Optional.empty());
  }

  @Test
  void runsAfterTheIdentityFilterSoTheCallerIsAlreadyBound() {
    assertThat(filter().getOrder()).isGreaterThan(identity.getOrder());
    assertThat(filter().getOrder()).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 30);
  }

  @Test
  void canBeSwitchedOffWithTheStarterAsAWhole() throws Exception {
    TenantScopeFilter f =
        new TenantScopeFilter(
            new TenancyProperties(
                false, null, null, null, null, null, null, null, null, null, null, null),
            new GatewayIdentityProperties(null, null, null));
    run(f, request("/v1/leases", FIRM, null, null));
    assertThat(seenInChain).containsExactly(Optional.empty());
  }
}
