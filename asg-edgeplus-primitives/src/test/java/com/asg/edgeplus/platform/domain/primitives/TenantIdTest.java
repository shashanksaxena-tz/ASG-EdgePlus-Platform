package com.asg.edgeplus.platform.domain.primitives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** TenantId — the unit of tenant isolation; corresponds to the gateway's X-Tenant-Id header. */
class TenantIdTest {

  @Test
  void rejectsNullValue() {
    assertThatNullPointerException().isThrownBy(() -> new TenantId(null));
  }

  @Test
  void rejectsNullString() {
    assertThatNullPointerException().isThrownBy(() -> TenantId.of(null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-a-uuid", "", "   ", "1234", "00000000-0000-0000-0000"})
  void rejectsMalformedUuidStrings(String value) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> TenantId.of(value))
        .withMessageContaining("not a valid tenant id");
  }

  @Test
  void acceptsACanonicalUuidString() {
    String uuid = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    assertThat(TenantId.of(uuid).value()).isEqualTo(UUID.fromString(uuid));
  }

  @Test
  void newIdGeneratesDistinctValues() {
    assertThat(TenantId.newId()).isNotEqualTo(TenantId.newId());
  }

  @Test
  void standardIsRecognised() {
    assertThat(TenantId.STANDARD.isStandard()).isTrue();
  }

  @Test
  void anArbitraryTenantIsNotStandard() {
    assertThat(TenantId.newId().isStandard()).isFalse();
  }

  @Test
  void standardHasTheReservedValue() {
    assertThat(TenantId.STANDARD.value())
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
  }

  @Test
  void toStringIsTheBareUuid() {
    String uuid = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    assertThat(TenantId.of(uuid)).hasToString(uuid);
  }

  @Test
  void equalityIsByValue() {
    String uuid = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    assertThat(TenantId.of(uuid)).isEqualTo(TenantId.of(uuid)).hasSameHashCodeAs(TenantId.of(uuid));
  }
}
