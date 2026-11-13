package com.asg.edgeplus.platform.tenancy;

import com.asg.edgeplus.platform.domain.primitives.TenantId;

/**
 * Says which spoke database a firm lives in. The starter owns routing and migration; it does not
 * own the registry of firms, which is Configuration-Service's, so how a firm maps to a database is
 * a seam rather than a decision made here.
 *
 * <p>Implementations must be pure and fast (called on the first request per firm only) and must
 * return a name in the {@code asg_spoke_*} namespace; anything else is refused before it reaches
 * SQL.
 */
@FunctionalInterface
public interface SpokeLocator {

  /** The database holding this firm's spoke. */
  String databaseName(TenantId tenant);
}
