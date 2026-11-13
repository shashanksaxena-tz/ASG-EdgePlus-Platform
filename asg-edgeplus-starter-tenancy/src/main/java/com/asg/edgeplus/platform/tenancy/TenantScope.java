package com.asg.edgeplus.platform.tenancy;

import com.asg.edgeplus.platform.domain.primitives.TenantId;
import java.util.Objects;

/**
 * What a unit of work is acting on: one firm's spoke, or the platform itself.
 *
 * <p>Deliberately has no "none" or "default" member. Work with no scope has nothing bound at all
 * ({@link TenantScopes#current()} is empty) and the routing DataSource refuses it, so a request
 * that forgot to say which firm it means can never be served from the wrong database (ADR-0059).
 */
public sealed interface TenantScope {

  /** Work inside one firm's spoke database. */
  record Firm(TenantId tenantId) implements TenantScope {
    public Firm {
      Objects.requireNonNull(tenantId, "tenantId");
    }

    @Override
    public String toString() {
      return "firm:" + tenantId.value();
    }
  }

  /** Cross-firm, platform-level work. Only ever entered explicitly. */
  record Platform() implements TenantScope {
    private static final Platform INSTANCE = new Platform();

    @Override
    public String toString() {
      return "platform";
    }
  }

  static TenantScope firm(TenantId tenantId) {
    return new Firm(tenantId);
  }

  static TenantScope platform() {
    return Platform.INSTANCE;
  }
}
