package com.asg.edgeplus.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.asg.edgeplus.platform.identity.IdentityAutoConfiguration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class TenancyAutoConfigurationTest {

  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(IdentityAutoConfiguration.class, TenancyAutoConfiguration.class))
          .withPropertyValues(
              "asg.platform.tenancy.jdbc-url-template=jdbc:postgresql://127.0.0.1:1/{db}",
              "asg.platform.tenancy.database-name-template=asg_spoke_{tenant}",
              "asg.platform.tenancy.schema=lease",
              "asg.platform.tenancy.username=svc_lease",
              "asg.platform.tenancy.password=test-only-pw",
              "asg.platform.tenancy.changelog=db/changelog/test-lease.yaml");

  @Test
  void wiresRoutingFromNothingMoreThanTheDocumentedProperties() {
    runner.run(
        ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx).hasSingleBean(SpokePools.class);
          assertThat(ctx.getBeansOfType(SpokePools.class).values()).doesNotContainNull();
          assertThat(ctx.getBeansOfType(SpokeSchemaMigrator.class).values()).doesNotContainNull();
          assertThat(ctx.getBeansOfType(SpokeLocator.class).values()).doesNotContainNull();
          assertThat(ctx).hasSingleBean(SpokeSchemaMigrator.class);
          assertThat(ctx).hasSingleBean(SpokeLocator.class);
          assertThat(ctx.getBean(SpokeLocator.class)).isInstanceOf(TemplateSpokeLocator.class);
          assertThat(ctx.getBean(DataSource.class)).isInstanceOf(TenantRoutingDataSource.class);
        });
  }

  @Test
  void theRoutingDataSourceIsThePrimaryOneSoJpaAndJdbcTemplateUseIt() {
    runner.run(
        ctx -> {
          assertThat(ctx.getBeansOfType(DataSource.class)).hasSize(1);
          assertThat(ctx.getBean("tenantRoutingDataSource")).isSameAs(ctx.getBean(DataSource.class));
        });
  }

  @Test
  void registersTheScopeFilterAfterTheIdentityFilter() {
    runner.run(
        ctx -> {
          @SuppressWarnings("unchecked")
          FilterRegistrationBean<TenantScopeFilter> reg =
              ctx.getBean("tenantScopeFilterRegistration", FilterRegistrationBean.class);
          assertThat(reg.getFilter()).isInstanceOf(TenantScopeFilter.class);
          assertThat(reg.getOrder()).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 30);
          assertThat(reg.getOrder())
              .isGreaterThan(ctx.getBean(com.asg.edgeplus.platform.identity.GatewayIdentityFilter.class).getOrder());
        });
  }

  @Test
  void doesNotAutoRegisterTheFilterASecondTimeAsAPlainFilterBean() {
    runner.run(ctx -> assertThat(ctx.getBeansOfType(TenantScopeFilter.class)).isEmpty());
  }

  @Test
  void backsOffEntirelyWhenDisabled() {
    runner
        .withPropertyValues("asg.platform.tenancy.enabled=false")
        .run(
            ctx -> {
              assertThat(ctx).doesNotHaveBean(SpokePools.class);
              assertThat(ctx).doesNotHaveBean(DataSource.class);
            });
  }

  @Test
  void aMissingRequiredPropertyFailsStartupNamingIt() {
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(IdentityAutoConfiguration.class, TenancyAutoConfiguration.class))
        .withPropertyValues("asg.platform.tenancy.schema=lease")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("asg.platform.tenancy.jdbc-url-template"));
  }

  @Test
  void aServiceSuppliedSpokeLocatorReplacesTheTemplateOne() {
    runner
        .withUserConfiguration(CustomLocator.class)
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(SpokeLocator.class);
              assertThat(ctx.getBean(SpokeLocator.class)).isNotInstanceOf(TemplateSpokeLocator.class);
            });
  }

  @Test
  void withACustomLocatorNoDatabaseNameTemplateIsNeeded() {
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(IdentityAutoConfiguration.class, TenancyAutoConfiguration.class))
        .withUserConfiguration(CustomLocator.class)
        .withPropertyValues(
            "asg.platform.tenancy.jdbc-url-template=jdbc:postgresql://127.0.0.1:1/{db}",
            "asg.platform.tenancy.schema=lease",
            "asg.platform.tenancy.username=svc_lease",
            "asg.platform.tenancy.password=test-only-pw",
            "asg.platform.tenancy.changelog=db/changelog/test-lease.yaml")
        .run(ctx -> assertThat(ctx).hasNotFailed());
  }

  @Test
  void withNeitherALocatorBeanNorATemplateStartupFailsNamingTheProperty() {
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(IdentityAutoConfiguration.class, TenancyAutoConfiguration.class))
        .withPropertyValues(
            "asg.platform.tenancy.jdbc-url-template=jdbc:postgresql://127.0.0.1:1/{db}",
            "asg.platform.tenancy.schema=lease",
            "asg.platform.tenancy.username=svc_lease",
            "asg.platform.tenancy.password=test-only-pw",
            "asg.platform.tenancy.changelog=db/changelog/test-lease.yaml")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("asg.platform.tenancy.database-name-template"));
  }

  @Test
  void aBeanNamedPlatformDataSourceServesPlatformScope() {
    runner
        .withUserConfiguration(PlatformDs.class)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              DataSource routing = ctx.getBean(DataSource.class);
              assertThat(routing).isInstanceOf(TenantRoutingDataSource.class);
              java.sql.Connection viaRouter =
                  TenantScopes.callIn(
                      TenantScope.platform(),
                      () -> {
                        try {
                          return routing.getConnection();
                        } catch (java.sql.SQLException e) {
                          throw new IllegalStateException(e);
                        }
                      });
              assertThat(viaRouter).isSameAs(PlatformDs.CONNECTION);
            });
  }

  @Test
  void platformScopeRolesBindFromACommaSeparatedProperty() {
    runner
        .withPropertyValues("asg.platform.tenancy.platform-scope-roles=SYSADMIN,SUPPORT")
        .run(
            ctx ->
                assertThat(ctx.getBean(TenancyProperties.class).platformScopeRoles())
                    .containsExactly("SYSADMIN", "SUPPORT"));
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomLocator {
    @Bean
    SpokeLocator customLocator() {
      return tenant -> "asg_spoke_custom";
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class PlatformDs {
    static final java.sql.Connection CONNECTION = org.mockito.Mockito.mock(java.sql.Connection.class);

    @Bean
    DataSource platformDataSource() throws java.sql.SQLException {
      DataSource ds = org.mockito.Mockito.mock(DataSource.class);
      org.mockito.Mockito.when(ds.getConnection()).thenReturn(CONNECTION);
      return ds;
    }
  }
}
