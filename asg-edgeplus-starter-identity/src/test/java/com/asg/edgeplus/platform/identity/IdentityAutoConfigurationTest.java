package com.asg.edgeplus.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

class IdentityAutoConfigurationTest {

  private final WebApplicationContextRunner web =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(IdentityAutoConfiguration.class));

  @Test
  void wiresTheFilterAndResolverIntoAServletApplication() {
    web.run(
        ctx -> {
          assertThat(ctx).hasSingleBean(GatewayIdentityFilter.class);
          assertThat(ctx).hasSingleBean(CallerContextArgumentResolver.class);
          assertThat(ctx).hasSingleBean(GatewayIdentityProperties.class);
        });
  }

  @Test
  void registersTheResolverWithSpringMvc() {
    web.run(
        ctx -> {
          java.util.List<HandlerMethodArgumentResolver> registered = new java.util.ArrayList<>();
          ctx.getBeansOfType(WebMvcConfigurer.class)
              .values()
              .forEach(c -> c.addArgumentResolvers(registered));
          assertThat(registered).anyMatch(CallerContextArgumentResolver.class::isInstance);
        });
  }

  @Test
  void backsOffWhenDisabled() {
    web.withPropertyValues("asg.platform.identity.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(GatewayIdentityFilter.class));
  }

  @Test
  void doesNothingOutsideAServletApplication() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(IdentityAutoConfiguration.class))
        .run(ctx -> assertThat(ctx).doesNotHaveBean(GatewayIdentityFilter.class));
  }

  @Test
  void aServiceSuppliedFilterWins() {
    web.withUserConfiguration(Custom.class)
        .run(ctx -> assertThat(ctx.getBean(GatewayIdentityFilter.class)).isSameAs(Custom.FILTER));
  }

  @Test
  void bindsPermitAllPathsFromProperties() {
    web.withPropertyValues("asg.platform.identity.permit-all-paths=/open/**")
        .run(
            ctx ->
                assertThat(ctx.getBean(GatewayIdentityProperties.class).permitAllPaths())
                    .containsExactly("/open/**"));
  }

  @Configuration(proxyBeanMethods = false)
  static class Custom {
    static final GatewayIdentityFilter FILTER =
        new GatewayIdentityFilter(new GatewayIdentityProperties(null, null, null));

    @Bean
    GatewayIdentityFilter gatewayIdentityFilter() {
      return FILTER;
    }
  }
}
