package com.asg.edgeplus.platform.identity;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the identity filter and the {@code CallerContext} argument resolver into any servlet web
 * application on the classpath. A service adds the dependency and gets both; there is nothing to
 * copy into its config package.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
    prefix = "asg.platform.identity",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(GatewayIdentityProperties.class)
public class IdentityAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public GatewayIdentityFilter gatewayIdentityFilter(GatewayIdentityProperties properties) {
    return new GatewayIdentityFilter(properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public CallerContextArgumentResolver callerContextArgumentResolver() {
    return new CallerContextArgumentResolver();
  }

  @Bean
  public WebMvcConfigurer callerContextWebMvcConfigurer(CallerContextArgumentResolver resolver) {
    return new WebMvcConfigurer() {
      @Override
      public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(resolver);
      }
    };
  }
}
