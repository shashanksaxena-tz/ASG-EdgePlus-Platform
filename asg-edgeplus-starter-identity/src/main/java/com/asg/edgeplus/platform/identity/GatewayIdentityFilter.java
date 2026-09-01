package com.asg.edgeplus.platform.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the gateway's identity headers to the request as a {@link CallerContext}.
 *
 * <p>Runs early so everything downstream — interceptors, controllers, audit — sees the same
 * identity. All parsing lives in {@link GatewayIdentityHeaders}; this class only pulls three
 * strings off the request and manages the binding lifecycle.
 *
 * <p>Fails closed: on a non-permit-all path with {@code requireIdentity} on, a missing or malformed
 * header means the request did not come through the gateway, so it is rejected rather than served
 * with an invented identity.
 */
public class GatewayIdentityFilter extends OncePerRequestFilter implements Ordered {

  /** Request attribute holding the {@link CallerContext}, for code that prefers the request. */
  public static final String ATTRIBUTE = CallerContext.class.getName();

  private final GatewayIdentityProperties properties;
  private final PathMatcher pathMatcher = new AntPathMatcher();

  public GatewayIdentityFilter(GatewayIdentityProperties properties) {
    this.properties = properties;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    boolean permitAll = isPermitAll(request);
    CallerContext ctx = null;

    try {
      ctx =
          GatewayIdentityHeaders.parse(
              request.getHeader(GatewayIdentityHeaders.USER_ID),
              request.getHeader(GatewayIdentityHeaders.TENANT_ID),
              request.getHeader(GatewayIdentityHeaders.ROLES));
    } catch (MissingGatewayIdentityException ex) {
      // A permit-all path legitimately arrives without identity: the gateway did not authenticate
      // it, so there is nothing to bind and nothing to complain about.
      if (!permitAll && properties.isRequireIdentity()) {
        throw ex;
      }
    }

    try {
      if (ctx != null) {
        request.setAttribute(ATTRIBUTE, ctx);
        CallerContextHolder.bind(ctx);
      }
      chain.doFilter(request, response);
    } finally {
      // Unconditional: a pooled platform thread must never carry one request's identity into the
      // next, and an exception mid-chain must not leave the binding behind.
      CallerContextHolder.clear();
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !properties.isEnabled();
  }

  private boolean isPermitAll(HttpServletRequest request) {
    String path = request.getRequestURI();
    return properties.permitAllPaths().stream().anyMatch(p -> pathMatcher.match(p, path));
  }

  /** Early, so audit interceptors and controllers all see the same bound identity. */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 20;
  }
}
