package com.asg.edgeplus.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CallerContextHolderTest {

  private static final CallerContext CALLER =
      new CallerContext(UUID.fromString("7d0c9b1e-1f55-4b53-9c1a-2a4a9f2f0001"), null, Set.of());

  @AfterEach
  void clean() {
    CallerContextHolder.clear();
  }

  @Test
  void isEmptyUntilBound() {
    assertThat(CallerContextHolder.current()).isEmpty();
  }

  @Test
  void requireFailsClearlyWhenNothingIsBound() {
    assertThatExceptionOfType(MissingGatewayIdentityException.class)
        .isThrownBy(CallerContextHolder::require)
        .satisfies(e -> assertThat(e.code()).isEqualTo("identity.gateway.headerMissing"));
  }

  @Test
  void returnsTheBoundCaller() {
    CallerContextHolder.bind(CALLER);
    assertThat(CallerContextHolder.current()).contains(CALLER);
    assertThat(CallerContextHolder.require()).isSameAs(CALLER);
  }

  @Test
  void clearRemovesTheBinding() {
    CallerContextHolder.bind(CALLER);
    CallerContextHolder.clear();
    assertThat(CallerContextHolder.current()).isEmpty();
  }
}
