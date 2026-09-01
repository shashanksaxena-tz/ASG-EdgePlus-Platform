package com.asg.edgeplus.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Defaults matter here: a service that sets nothing must still fail closed. */
class GatewayIdentityPropertiesTest {

  @Test
  void defaultsToEnabledAndRequiringIdentity() {
    GatewayIdentityProperties p = new GatewayIdentityProperties(null, null, null);
    assertThat(p.isEnabled()).isTrue();
    assertThat(p.isRequireIdentity()).isTrue();
  }

  @Test
  void defaultPermitAllCoversHealthAndDocs() {
    GatewayIdentityProperties p = new GatewayIdentityProperties(null, null, null);
    assertThat(p.permitAllPaths())
        .contains("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html");
  }

  @Test
  void anEmptyPermitAllListFallsBackToTheDefaultsRatherThanLockingEverythingOut() {
    GatewayIdentityProperties p = new GatewayIdentityProperties(null, null, List.of());
    assertThat(p.permitAllPaths()).isNotEmpty();
  }

  @Test
  void anExplicitPermitAllListReplacesTheDefaults() {
    GatewayIdentityProperties p = new GatewayIdentityProperties(null, null, List.of("/custom/**"));
    assertThat(p.permitAllPaths()).containsExactly("/custom/**");
  }

  @Test
  void canBeDisabled() {
    assertThat(new GatewayIdentityProperties(false, null, null).isEnabled()).isFalse();
  }

  @Test
  void requireIdentityCanBeRelaxedForLocalDevelopment() {
    assertThat(new GatewayIdentityProperties(null, false, null).isRequireIdentity()).isFalse();
  }

  @Test
  void thePermitAllListIsImmutable() {
    GatewayIdentityProperties p = new GatewayIdentityProperties(null, null, List.of("/a/**"));
    org.assertj.core.api.Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> p.permitAllPaths().add("/b/**"));
  }
}
