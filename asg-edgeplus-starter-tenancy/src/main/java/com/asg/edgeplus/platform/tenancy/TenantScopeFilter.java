package com.asg.edgeplus.platform.tenancy;

import com.asg.edgeplus.platform.identity.CallerContext;
import com.asg.edgeplus.platform.identity.CallerContextHolder;
import com.asg.edgeplus.platform.identity.GatewayIdentityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the request's {@link TenantScope}, from the identity the gateway established.
 *
 * <ul>
 *   <li>Caller has a tenant: firm scope. The gateway derives {@code X-Tenant-Id} from the validated
 *       JWT, so a caller cannot name a firm that is not theirs.
 *   <li>{@code X-Tenant-Scope: platform}: platform scope, but only for a caller holding one of the
 *       configured {@code platform-scope-roles}; otherwise 403. Any other value of the header is a
 *       400, never a silent fallback to the firm.
 *   <li>Neither: <strong>nothing is bound</strong>. Platform scope is never inferred from a missing
 *       tenant (ADR-0059); a database access in such a request is refused by the routing
 *       DataSource.
 * </ul>
 *
 * Permit-all paths (health, docs) are left unscoped, using the identity starter's own list so the
 * two cannot disagree about what is public.
 */
public class TenantScopeFilter extends OncePerRequestFilter implements Ordered {

  /** Opt-in header for platform scope. Absence means "act for the caller's firm". */
  public static final String SCOPE_HEADER = "X-Tenant-Scope";

  private static final String PLATFORM = "platform";

  private final TenancyProperties properties;
  private final GatewayIdentityProperties identityProperties;
  private final PathMatcher pathMatcher = new AntPathMatcher();

  public TenantScopeFilter(
      TenancyProperties properties, GatewayIdentityProperties identityProperties) {
    this.properties = properties;
    this.identityProperties = identityProperties;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<CallerContext> caller = CallerContextHolder.current();
    String requested = request.getHeader(SCOPE_HEADER);
    TenantScope scope = null;

    if (requested != null) {
      if (!PLATFORM.equalsIgnoreCase(requested.trim())) {
        response.sendError(
            HttpServletResponse.SC_BAD_REQUEST,
            SCOPE_HEADER + " must be '" + PLATFORM + "' or absent");
        return;
      }
      if (caller.isEmpty() || !caller.get().hasAnyRole(platformRoles())) {
        response.sendError(
            HttpServletResponse.SC_FORBIDDEN, "caller may not act in platform scope");
        return;
      }
      scope = TenantScope.platform();
    } else if (caller.isPresent() && caller.get().hasTenant()) {
      scope = TenantScope.firm(caller.get().requireTenant());
    }

    try {
      if (scope != null) {
        TenantScopes.bind(scope);
      }
      chain.doFilter(request, response);
    } finally {
      // Unconditional: a pooled request thread must never carry one request's routing into the next.
      TenantScopes.clear();
    }
  }

  private String[] platformRoles() {
    return properties.platformScopeRoles().toArray(String[]::new);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!properties.isEnabled()) {
      return true;
    }
    String path = request.getRequestURI();
    return identityProperties.permitAllPaths().stream().anyMatch(p -> pathMatcher.match(p, path));
  }

  /** After the identity filter (HIGHEST_PRECEDENCE + 20), which binds the caller this reads. */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 30;
  }
}
