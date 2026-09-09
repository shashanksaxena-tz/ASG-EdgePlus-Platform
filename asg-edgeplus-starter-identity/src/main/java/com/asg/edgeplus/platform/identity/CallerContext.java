package com.asg.edgeplus.platform.identity;

import com.asg.edgeplus.platform.domain.primitives.TenantId;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who is making this request, as established by the API gateway.
 *
 * <p>The gateway validates the Cognito JWT and then puts the caller's identity on the request as
 * headers, stripping the {@code Authorization} header on the way through — so a downstream service
 * never sees, and must never try to validate, a token. This record is that identity.
 *
 * @param userId the authenticated subject; the actor recorded in audit trails
 * @param tenantId the caller's active tenant, from the JWT's {@code tid} claim. <strong>Nullable
 *     by design</strong>: a platform-level caller (a SYSADMIN acting outside any single firm, e.g.
 *     a cross-client operation) legitimately has no active tenant, so a service must decide
 *     explicitly whether an endpoint tolerates that rather than having a default invented for it.
 *     Use {@link #hasTenant()} or {@link #requireTenant()}.
 * @param roles the caller's roles, never null and never containing blanks
 */
public record CallerContext(UUID userId, TenantId tenantId, Set<String> roles) {

  public CallerContext {
    Objects.requireNonNull(userId, "CallerContext.userId");
    Objects.requireNonNull(roles, "CallerContext.roles");
    // Set.copyOf does not preserve iteration order (it's deliberately randomized per JVM run) --
    // GatewayIdentityHeaders.parseRoles() uses a LinkedHashSet specifically for readable logs, so
    // that order must survive here too.
    roles = Collections.unmodifiableSet(new LinkedHashSet<>(roles));
  }

  /** {@code true} when the caller is acting inside a specific tenant. */
  public boolean hasTenant() {
    return tenantId != null;
  }

  /**
   * The caller's active tenant.
   *
   * @throws MissingGatewayIdentityException if the caller has no active tenant — a clearer failure
   *     than a {@code NullPointerException} three frames deeper in a repository
   */
  public TenantId requireTenant() {
    if (tenantId == null) {
      throw MissingGatewayIdentityException.noActiveTenant(userId);
    }
    return tenantId;
  }

  /** Case-sensitive role check. Roles arrive exactly as the identity provider spells them. */
  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  /** {@code true} if the caller holds at least one of the given roles. */
  public boolean hasAnyRole(String... candidates) {
    for (String candidate : candidates) {
      if (roles.contains(candidate)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Deliberately omits nothing sensitive — there is no token here to leak, because the gateway
   * already removed it. Safe to log.
   */
  @Override
  public String toString() {
    return "CallerContext[userId=" + userId + ", tenantId=" + tenantId + ", roles=" + roles + "]";
  }
}
