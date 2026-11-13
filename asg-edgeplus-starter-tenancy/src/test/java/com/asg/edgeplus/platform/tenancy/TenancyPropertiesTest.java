package com.asg.edgeplus.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TenancyPropertiesTest {

  @Test
  void isEnabledAndHasSmallPoolsByDefault() {
    TenancyProperties p = TestProperties.valid();
    assertThat(p.isEnabled()).isTrue();
    assertThat(p.poolMaxSize()).isEqualTo(3);
    assertThat(p.poolIdleTimeout()).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  void explicitPoolSettingsAndRolesAreKept() {
    TenancyProperties p =
        new TenancyProperties(
            null, null, null, null, null, null, null, null, null, 7, Duration.ofSeconds(42),
            List.of("OPS"));
    assertThat(p.poolMaxSize()).isEqualTo(7);
    assertThat(p.poolIdleTimeout()).isEqualTo(Duration.ofSeconds(42));
    assertThat(p.platformScopeRoles()).containsExactly("OPS");
  }

  @Test
  void canBeDisabled() {
    TenancyProperties p =
        new TenancyProperties(
            false, null, null, null, null, null, null, null, null, null, null, null);
    assertThat(p.isEnabled()).isFalse();
  }

  @Test
  void platformScopeIsOffUnlessRolesAreConfigured() {
    assertThat(TestProperties.valid().platformScopeRoles()).isEmpty();
  }

  @Test
  void platformScopeRolesAreImmutable() {
    List<String> roles = TestProperties.withPlatformRoles(List.of("SYSADMIN")).platformScopeRoles();
    org.assertj.core.api.Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> roles.add("X"));
  }

  @Test
  void carriesNoCredentialsByDefault() {
    TenancyProperties p =
        new TenancyProperties(
            null, null, null, null, null, null, null, null, null, null, null, null);
    assertThat(p.username()).isNull();
    assertThat(p.password()).isNull();
    assertThat(p.adminUsername()).isNull();
    assertThat(p.adminPassword()).isNull();
  }

  @Test
  void aCompleteConfigurationValidates() {
    assertThatCode(() -> TestProperties.valid().validate()).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  void aMissingJdbcUrlTemplateIsRefusedByName(String blank) {
    assertThatIllegalStateException()
        .isThrownBy(() -> TestProperties.withUrlTemplate(blank).validate())
        .withMessageContaining("asg.platform.tenancy.jdbc-url-template");
  }

  @Test
  void aJdbcUrlTemplateWithoutTheDatabasePlaceholderIsRefused() {
    assertThatIllegalStateException()
        .isThrownBy(
            () -> TestProperties.withUrlTemplate("jdbc:postgresql://h/asg_spoke_x").validate())
        .withMessageContaining("{db}");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void aMissingSchemaIsRefusedByName(String blank) {
    assertThatIllegalStateException()
        .isThrownBy(() -> TestProperties.withSchema(blank).validate())
        .withMessageContaining("asg.platform.tenancy.schema");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"Lease", "1lease", "lease-accounting", "lease;drop", "public ", "a\"b", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
  void anUnsafeSchemaNameIsRefused(String schema) {
    assertThatIllegalStateException()
        .isThrownBy(() -> TestProperties.withSchema(schema).validate())
        .withMessageContaining("asg.platform.tenancy.schema");
  }

  @ParameterizedTest
  @ValueSource(strings = {"lease", "lease_accounting", "a", "x2", "a_1"})
  void aSafeSchemaNameIsAccepted(String schema) {
    assertThatCode(() -> TestProperties.withSchema(schema).validate()).doesNotThrowAnyException();
  }

  @Test
  void aSchemaOfExactlyTheMaximumLengthIsAccepted() {
    assertThatCode(() -> TestProperties.withSchema("a".repeat(63)).validate())
        .doesNotThrowAnyException();
  }

  @Test
  void schemasReservedByPostgresAreRefused() {
    for (String reserved : List.of("public", "information_schema", "pg_catalog", "pg_temp")) {
      assertThatIllegalStateException()
          .as(reserved)
          .isThrownBy(() -> TestProperties.withSchema(reserved).validate())
          .withMessageContaining("asg.platform.tenancy.schema");
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void aMissingServiceLoginIsRefusedByName(String blank) {
    assertThatIllegalStateException()
        .isThrownBy(() -> TestProperties.withCredentials(blank, "pw").validate())
        .withMessageContaining("asg.platform.tenancy.username");
    assertThatIllegalStateException()
        .isThrownBy(() -> TestProperties.withCredentials("u", blank).validate())
        .withMessageContaining("asg.platform.tenancy.password");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void aMissingChangelogIsRefusedByName(String blank) {
    assertThatIllegalStateException()
        .isThrownBy(() -> TestProperties.withChangelog(blank).validate())
        .withMessageContaining("asg.platform.tenancy.changelog");
  }

  @Test
  void adminCredentialsMustComeAsAPair() {
    assertThatIllegalStateException()
        .isThrownBy(() -> TestProperties.withAdmin("provisioner", null).validate())
        .withMessageContaining("admin-username")
        .withMessageContaining("admin-password");
    assertThatIllegalStateException()
        .isThrownBy(() -> TestProperties.withAdmin(null, "pw").validate())
        .withMessageContaining("admin-password");
    assertThatCode(() -> TestProperties.withAdmin("provisioner", "pw").validate())
        .doesNotThrowAnyException();
  }

  @Test
  void aBlankAdminPairCountsAsNoAdmin() {
    assertThat(TestProperties.withAdmin(" ", " ").hasAdmin()).isFalse();
    assertThat(TestProperties.withAdmin("p", "pw").hasAdmin()).isTrue();
    assertThat(TestProperties.valid().hasAdmin()).isFalse();
  }

  @Test
  void aPoolSizeBelowOneIsRefused() {
    assertThatIllegalStateException()
        .isThrownBy(() -> TestProperties.withPool(0, null).validate())
        .withMessageContaining("pool-max-size");
    assertThatCode(() -> TestProperties.withPool(1, null).validate()).doesNotThrowAnyException();
  }

  @Test
  void aNonPositiveIdleTimeoutIsRefused() {
    assertThatIllegalStateException()
        .isThrownBy(() -> TestProperties.withPool(null, Duration.ZERO).validate())
        .withMessageContaining("pool-idle-timeout");
  }

  @Test
  void resolvesTheJdbcUrlForADatabase() {
    assertThat(TestProperties.valid().jdbcUrl("asg_spoke_abc"))
        .isEqualTo("jdbc:postgresql://localhost:5432/asg_spoke_abc");
  }

  @Test
  void refusesToBuildAJdbcUrlForAnUnsafeDatabaseName() {
    TenancyProperties p = TestProperties.valid();
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> p.jdbcUrl("asg_spoke_x?evil=1"))
        .withMessageContaining("unsafe");
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> p.jdbcUrl("postgres"));
  }

  @Test
  void toStringNeverPrintsAPassword() {
    String text = TestProperties.withAdmin("provisioner", "admin-secret-1").toString();

    assertThat(text).doesNotContain("admin-secret-1").doesNotContain(TestProperties.valid().password());
    assertThat(text).contains("password=****").contains("adminUsername=provisioner");
  }
}
