package com.asg.edgeplus.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import jakarta.servlet.FilterChain;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GatewayIdentityFilterTest {

  private static final String USER = "7d0c9b1e-1f55-4b53-9c1a-2a4a9f2f0001";
  private static final String TENANT = "11111111-2222-3333-4444-555555555555";

  private final List<Optional<CallerContext>> seen = new ArrayList<>();
  private final FilterChain chain = (rq, rs) -> seen.add(CallerContextHolder.current());

  private static GatewayIdentityFilter filter(Boolean enabled, Boolean requireIdentity) {
    return new GatewayIdentityFilter(new GatewayIdentityProperties(enabled, requireIdentity, null));
  }

  private static MockHttpServletRequest request(String path, String user, String tenant) {
    MockHttpServletRequest r = new MockHttpServletRequest("GET", path);
    r.setRequestURI(path);
    if (user != null) {
      r.addHeader("X-User-Id", user);
    }
    if (tenant != null) {
      r.addHeader("X-Tenant-Id", tenant);
    }
    r.addHeader("X-Roles", "ADMIN,USER");
    return r;
  }

  @Test
  void bindsTheGatewayIdentityForTheDurationOfTheRequestAndExposesItAsAnAttribute() throws Exception {
    MockHttpServletRequest req = request("/v1/things", USER, TENANT);

    filter(null, null).doFilter(req, new MockHttpServletResponse(), chain);

    CallerContext ctx = seen.get(0).orElseThrow();
    assertThat(ctx.userId()).hasToString(USER);
    assertThat(ctx.tenantId().value()).hasToString(TENANT);
    assertThat(ctx.roles()).containsExactly("ADMIN", "USER");
    assertThat(req.getAttribute(GatewayIdentityFilter.ATTRIBUTE)).isEqualTo(ctx);
  }

  @Test
  void clearsTheBindingAfterTheRequest() throws Exception {
    filter(null, null).doFilter(request("/v1/things", USER, TENANT), new MockHttpServletResponse(), chain);
    assertThat(CallerContextHolder.current()).isEmpty();
  }

  @Test
  void clearsTheBindingWhenTheChainThrows() {
    FilterChain failing =
        (rq, rs) -> {
          throw new IllegalStateException("boom");
        };
    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                filter(null, null)
                    .doFilter(request("/v1/things", USER, TENANT), new MockHttpServletResponse(), failing));
    assertThat(CallerContextHolder.current()).isEmpty();
  }

  @Test
  void rejectsAnUnauthenticatedRequestToAProtectedPathBecauseItBypassedTheGateway() {
    assertThatExceptionOfType(MissingGatewayIdentityException.class)
        .isThrownBy(
            () ->
                filter(null, null)
                    .doFilter(request("/v1/things", null, null), new MockHttpServletResponse(), chain))
        .satisfies(e -> assertThat(e.code()).isEqualTo("identity.gateway.headerMissing"));
    assertThat(seen).isEmpty();
  }

  @Test
  void rejectsAMalformedUserId() {
    assertThatExceptionOfType(MissingGatewayIdentityException.class)
        .isThrownBy(
            () ->
                filter(null, null)
                    .doFilter(
                        request("/v1/things", "not-a-uuid", null),
                        new MockHttpServletResponse(),
                        chain))
        .satisfies(e -> assertThat(e.code()).isEqualTo("identity.gateway.headerMalformed"));
  }

  @Test
  void letsPermitAllPathsThroughWithNoIdentity() throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();

    filter(null, null).doFilter(request("/actuator/health", null, null), res, chain);

    assertThat(seen).containsExactly(Optional.empty());
  }

  @Test
  void bindsIdentityOnAPermitAllPathWhenTheGatewayDidSendIt() throws Exception {
    filter(null, null).doFilter(request("/actuator/health", USER, null), new MockHttpServletResponse(), chain);
    assertThat(seen.get(0)).isPresent();
  }

  @Test
  void canBeToldNotToRequireIdentityForLocalDevelopment() throws Exception {
    filter(null, false).doFilter(request("/v1/things", null, null), new MockHttpServletResponse(), chain);
    assertThat(seen).containsExactly(Optional.empty());
  }

  @Test
  void doesNothingWhenDisabled() throws Exception {
    filter(false, null).doFilter(request("/v1/things", USER, TENANT), new MockHttpServletResponse(), chain);
    assertThat(seen).containsExactly(Optional.empty());
  }

  @Test
  void runsEarly() {
    assertThat(filter(null, null).getOrder()).isLessThan(0);
  }
}
