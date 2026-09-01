package com.asg.edgeplus.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.asg.edgeplus.platform.domain.primitives.TenantId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The parsing contract with ASG-Edgeplus-Api-Gateway's IdentityHeaderFilter. If this test and that
 * filter disagree, every service authenticates wrongly — so the header names are asserted too.
 */
class GatewayIdentityHeadersTest {

  private static final String USER = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
  private static final String TENANT = "11111111-2222-3333-4444-555555555555";

  @Test
  void headerNamesMatchTheGatewayExactly() {
    assertThat(GatewayIdentityHeaders.USER_ID).isEqualTo("X-User-Id");
    assertThat(GatewayIdentityHeaders.TENANT_ID).isEqualTo("X-Tenant-Id");
    assertThat(GatewayIdentityHeaders.ROLES).isEqualTo("X-Roles");
  }

  @Test
  void parsesAFullyPopulatedIdentity() {
    CallerContext c = GatewayIdentityHeaders.parse(USER, TENANT, "SYSADMIN,LEASE_ADMIN_MANAGER");
    assertThat(c.userId()).isEqualTo(UUID.fromString(USER));
    assertThat(c.tenantId()).isEqualTo(TenantId.of(TENANT));
    assertThat(c.roles()).containsExactly("SYSADMIN", "LEASE_ADMIN_MANAGER");
  }

  @Test
  void roleChecksAreCaseSensitive() {
    CallerContext c = GatewayIdentityHeaders.parse(USER, TENANT, "SYSADMIN");
    assertThat(c.hasRole("SYSADMIN")).isTrue();
    assertThat(c.hasRole("sysadmin")).isFalse();
  }

  @Test
  void hasAnyRoleFindsOneOfSeveral() {
    CallerContext c = GatewayIdentityHeaders.parse(USER, TENANT, "LEASE_ADMIN_MANAGER");
    assertThat(c.hasAnyRole("SYSADMIN", "LEASE_ADMIN_MANAGER")).isTrue();
    assertThat(c.hasAnyRole("SYSADMIN", "CLIENT_ADMIN")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", ",", ",,"})
  void anEmptyRolesHeaderYieldsNoRoles(String rolesHeader) {
    assertThat(GatewayIdentityHeaders.parse(USER, TENANT, rolesHeader).roles()).isEmpty();
  }

  @Test
  void aNullRolesHeaderYieldsNoRoles() {
    assertThat(GatewayIdentityHeaders.parse(USER, TENANT, null).roles()).isEmpty();
  }

  @Test
  void trimsRolesAndDropsEmptySegments() {
    assertThat(GatewayIdentityHeaders.parse(USER, TENANT, "  A , , B ,").roles())
        .containsExactly("A", "B");
  }

  @Test
  void collapsesDuplicateRoles() {
    assertThat(GatewayIdentityHeaders.parse(USER, TENANT, "A,A,A").roles()).containsExactly("A");
  }

  @Test
  void theRolesSetIsImmutable() {
    CallerContext c = GatewayIdentityHeaders.parse(USER, TENANT, "A");
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> c.roles().add("HACK"));
  }

  // ---- a platform caller with no active tenant is legitimate, not an error ----

  @ParameterizedTest
  @ValueSource(strings = {"null", "NULL", "", "   "})
  void anAbsentTenantClaimMeansNoActiveTenant(String tenantHeader) {
    CallerContext c = GatewayIdentityHeaders.parse(USER, tenantHeader, "SYSADMIN");
    assertThat(c.hasTenant()).isFalse();
    assertThat(c.tenantId()).isNull();
  }

  @Test
  void aNullTenantHeaderMeansNoActiveTenant() {
    assertThat(GatewayIdentityHeaders.parse(USER, null, "SYSADMIN").hasTenant()).isFalse();
  }

  @Test
  void requireTenantFailsLoudlyForAPlatformCaller() {
    CallerContext c = GatewayIdentityHeaders.parse(USER, null, "SYSADMIN");
    assertThatExceptionOfType(MissingGatewayIdentityException.class)
        .isThrownBy(c::requireTenant)
        .satisfies(e -> assertThat(e.code()).isEqualTo("identity.tenant.absent"));
  }

  // ---- fails closed when the gateway was bypassed ----

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void aBlankUserIdIsRejected(String userHeader) {
    assertThatExceptionOfType(MissingGatewayIdentityException.class)
        .isThrownBy(() -> GatewayIdentityHeaders.parse(userHeader, TENANT, "A"))
        .satisfies(e -> assertThat(e.code()).isEqualTo("identity.gateway.headerMissing"));
  }

  @Test
  void aMissingUserIdIsRejected() {
    assertThatExceptionOfType(MissingGatewayIdentityException.class)
        .isThrownBy(() -> GatewayIdentityHeaders.parse(null, TENANT, "A"))
        .satisfies(e -> assertThat(e.code()).isEqualTo("identity.gateway.headerMissing"));
  }

  @Test
  void aMalformedUserIdIsRejected() {
    assertThatExceptionOfType(MissingGatewayIdentityException.class)
        .isThrownBy(() -> GatewayIdentityHeaders.parse("not-a-uuid", TENANT, "A"))
        .satisfies(e -> assertThat(e.code()).isEqualTo("identity.gateway.headerMalformed"));
  }

  @Test
  void aMalformedTenantIdIsRejected() {
    assertThatExceptionOfType(MissingGatewayIdentityException.class)
        .isThrownBy(() -> GatewayIdentityHeaders.parse(USER, "not-a-uuid", "A"))
        .satisfies(e -> assertThat(e.code()).isEqualTo("identity.gateway.headerMalformed"));
  }

  @Test
  void toStringCarriesNoToken() {
    assertThat(GatewayIdentityHeaders.parse(USER, TENANT, "A").toString().toLowerCase())
        .doesNotContain("bearer");
  }
}
