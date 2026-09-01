package com.asg.edgeplus.platform.identity;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link GatewayIdentityFilter}.
 *
 * <p>Header names are deliberately <em>not</em> configurable — they are a platform contract with
 * the gateway, and making them a per-service setting would let two services disagree about what
 * identity looks like. See {@link GatewayIdentityHeaders}.
 *
 * @param enabled turn the filter off entirely. Intended for slice tests, not for production.
 * @param requireIdentity when true (the default) a request to a non-permit-all path without valid
 *     identity headers is rejected, because it means the request bypassed the gateway. Setting
 *     this false is only sensible for local development against a service run without a gateway.
 * @param permitAllPaths Ant patterns that do not require identity — health checks, docs. Must stay
 *     aligned with the gateway's own {@code asg.gateway.security.permit-all-paths}: a path the
 *     gateway lets through unauthenticated will arrive here with no identity headers, so if it is
 *     not listed here the service will reject a request the gateway deliberately allowed.
 */
@ConfigurationProperties(prefix = "asg.platform.identity")
public record GatewayIdentityProperties(
    Boolean enabled, Boolean requireIdentity, List<String> permitAllPaths) {

  private static final List<String> DEFAULT_PERMIT_ALL =
      List.of("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html");

  public GatewayIdentityProperties {
    enabled = enabled == null ? Boolean.TRUE : enabled;
    requireIdentity = requireIdentity == null ? Boolean.TRUE : requireIdentity;
    permitAllPaths =
        permitAllPaths == null || permitAllPaths.isEmpty()
            ? DEFAULT_PERMIT_ALL
            : List.copyOf(permitAllPaths);
  }

  public boolean isEnabled() {
    return Boolean.TRUE.equals(enabled);
  }

  public boolean isRequireIdentity() {
    return Boolean.TRUE.equals(requireIdentity);
  }
}
