package com.asg.edgeplus.platform.tenancy;

import com.asg.edgeplus.platform.identity.GatewayIdentityProperties;
import com.asg.edgeplus.platform.identity.IdentityAutoConfiguration;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;

/**
 * Wires per-firm spoke routing: the routing {@code DataSource} (primary, so JPA and
 * {@code JdbcTemplate} use it), the lazy per-firm pools, the first-use migrator and, in a servlet
 * application, the filter that binds each request's {@link TenantScope}.
 *
 * <p>Runs before Boot's own DataSource auto-configuration, which then backs off. A service must
 * therefore also set {@code spring.liquibase.enabled=false}: Liquibase at startup has no firm to
 * migrate, and this starter runs the changelog per firm on first use instead.
 */
@AutoConfiguration(
    after = IdentityAutoConfiguration.class,
    beforeName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ConditionalOnProperty(
    prefix = "asg.platform.tenancy",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(TenancyProperties.class)
public class TenancyAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public SpokeLocator spokeLocator(TenancyProperties properties) {
    properties.validate();
    return new TemplateSpokeLocator(properties.databaseNameTemplate());
  }

  @Bean
  @ConditionalOnMissingBean(SpokeProvisioner.class)
  public SpokeSchemaMigrator spokeSchemaMigrator(TenancyProperties properties) {
    properties.validate();
    return new SpokeSchemaMigrator(properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public SpokePools spokePools(
      SpokeLocator locator, SpokeProvisioner provisioner, TenancyProperties properties) {
    return new SpokePools(locator, provisioner, properties);
  }

  /**
   * Platform scope is served by a service's own bean named {@code platformDataSource}, if it
   * declares one; otherwise platform scope is refused.
   */
  @Bean
  @Primary
  public DataSource tenantRoutingDataSource(
      SpokePools pools,
      TenancyProperties properties,
      @Qualifier("platformDataSource") ObjectProvider<DataSource> platformDataSource) {
    // Validated here as well, because this bean is the one a service cannot replace: a service
    // that declares its own locator and provisioner would otherwise skip validation entirely.
    properties.validate();
    return new TenantRoutingDataSource(pools, platformDataSource.getIfAvailable());
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  static class ScopeFilterConfiguration {

    /**
     * Registered rather than exposed as a Filter bean, so it is added to the chain exactly once and
     * ordered explicitly after the identity filter.
     */
    @Bean
    FilterRegistrationBean<TenantScopeFilter> tenantScopeFilterRegistration(
        TenancyProperties properties, GatewayIdentityProperties identityProperties) {
      FilterRegistrationBean<TenantScopeFilter> registration =
          new FilterRegistrationBean<>(new TenantScopeFilter(properties, identityProperties));
      registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
      return registration;
    }
  }
}
