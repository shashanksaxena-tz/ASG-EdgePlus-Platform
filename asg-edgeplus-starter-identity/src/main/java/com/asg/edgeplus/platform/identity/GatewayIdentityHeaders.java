package com.asg.edgeplus.platform.identity;

import com.asg.edgeplus.platform.domain.primitives.TenantId;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Parses the gateway's identity headers into a {@link CallerContext}.
 *
 * <p>Deliberately pure and static — no Spring, no servlet API — so the whole parsing contract is
 * unit-testable without a container, and the Spring filter above it stays a thin shell that only
 * pulls three strings off a request. The header <em>names</em> are constants here because they are
 * a platform contract, not a per-service setting: they must match
 * {@code ASG-Edgeplus-Api-Gateway}'s {@code IdentityHeaderFilter} exactly.
 */
public final class GatewayIdentityHeaders {

  /** The authenticated subject, from the JWT's {@code sub}. */
  public static final String USER_ID = "X-User-Id";

  /** The active tenant, from the JWT's {@code tid} claim. May be absent for platform callers. */
  public static final String TENANT_ID = "X-Tenant-Id";

  /** Comma-separated roles, from the JWT's {@code roles} claim. May be empty. */
  public static final String ROLES = "X-Roles";

  /**
   * Build a {@link CallerContext} from three raw header values.
   *
   * @param userIdHeader value of {@value #USER_ID}; required
   * @param tenantIdHeader value of {@value #TENANT_ID}; may be null or blank for a platform caller
   * @param rolesHeader value of {@value #ROLES}; may be null or blank for a caller with no roles
   * @throws MissingGatewayIdentityException if the user id is absent, or any present header is
   *     malformed
   */
  public static CallerContext parse(
      String userIdHeader, String tenantIdHeader, String rolesHeader) {

    if (isBlank(userIdHeader)) {
      throw MissingGatewayIdentityException.missingHeader(USER_ID);
    }

    UUID userId;
    try {
      userId = UUID.fromString(userIdHeader.trim());
    } catch (IllegalArgumentException ex) {
      throw MissingGatewayIdentityException.malformedHeader(USER_ID, "not a UUID");
    }

    TenantId tenantId = null;
    if (!isBlank(tenantIdHeader)) {
      // The gateway sets this from a JWT claim, so "null" as a literal string is a real
      // possibility when the claim is absent — treat it as absent rather than as a malformed UUID.
      String raw = tenantIdHeader.trim();
      if (!"null".equalsIgnoreCase(raw)) {
        try {
          tenantId = TenantId.of(raw);
        } catch (IllegalArgumentException ex) {
          throw MissingGatewayIdentityException.malformedHeader(TENANT_ID, "not a UUID");
        }
      }
    }

    return new CallerContext(userId, tenantId, parseRoles(rolesHeader));
  }

  /**
   * Splits the comma-separated roles header. Order is preserved for readable logs, blanks are
   * dropped, and duplicates collapse.
   */
  static Set<String> parseRoles(String rolesHeader) {
    Set<String> roles = new LinkedHashSet<>();
    if (isBlank(rolesHeader)) {
      return roles;
    }
    for (String part : rolesHeader.split(",")) {
      String role = part.trim();
      if (!role.isEmpty()) {
        roles.add(role);
      }
    }
    return roles;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private GatewayIdentityHeaders() {}
}
