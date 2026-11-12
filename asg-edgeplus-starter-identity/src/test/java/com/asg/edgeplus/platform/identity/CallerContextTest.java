package com.asg.edgeplus.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.asg.edgeplus.platform.domain.primitives.TenantId;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CallerContextTest {

  private static final UUID USER = UUID.fromString("7d0c9b1e-1f55-4b53-9c1a-2a4a9f2f0001");

  @Test
  void aCallerInsideATenantReportsItAndReturnsIt() {
    CallerContext c = new CallerContext(USER, TenantId.STANDARD, Set.of("USER"));
    assertThat(c.hasTenant()).isTrue();
    assertThat(c.requireTenant()).isEqualTo(TenantId.STANDARD);
  }

  @Test
  void aPlatformCallerHasNoTenantAndRequiringOneFailsClearly() {
    CallerContext c = new CallerContext(USER, null, Set.of("SYSADMIN"));
    assertThat(c.hasTenant()).isFalse();
    assertThatExceptionOfType(MissingGatewayIdentityException.class)
        .isThrownBy(c::requireTenant)
        .satisfies(e -> assertThat(e.code()).isEqualTo("identity.tenant.absent"))
        .withMessageContaining(USER.toString());
  }

  @Test
  void rolesAreCaseSensitive() {
    CallerContext c = new CallerContext(USER, null, Set.of("Admin"));
    assertThat(c.hasRole("Admin")).isTrue();
    assertThat(c.hasRole("ADMIN")).isFalse();
  }

  @Test
  void hasAnyRoleMatchesOnOneOfSeveralAndFailsOnNone() {
    CallerContext c = new CallerContext(USER, null, Set.of("A", "B"));
    assertThat(c.hasAnyRole("X", "B")).isTrue();
    assertThat(c.hasAnyRole("X", "Y")).isFalse();
    assertThat(c.hasAnyRole()).isFalse();
  }

  @Test
  void rolesKeepTheirOrderAndCannotBeModified() {
    Set<String> ordered = new LinkedHashSet<>(java.util.List.of("Z", "A", "M"));
    CallerContext c = new CallerContext(USER, null, ordered);
    assertThat(c.roles()).containsExactly("Z", "A", "M");
    org.assertj.core.api.Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> c.roles().add("X"));
  }

  @Test
  void requiresAUserAndRoles() {
    assertThatNullPointerException().isThrownBy(() -> new CallerContext(null, null, Set.of()));
    assertThatNullPointerException().isThrownBy(() -> new CallerContext(USER, null, null));
  }

  @Test
  void describesItselfForLogs() {
    assertThat(new CallerContext(USER, TenantId.STANDARD, Set.of("USER")).toString())
        .contains(USER.toString())
        .contains("USER");
  }
}
