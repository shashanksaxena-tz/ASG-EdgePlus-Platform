package com.asg.edgeplus.platform.tenancy;

import com.asg.edgeplus.platform.domain.primitives.TenantId;

/**
 * A firm's spoke could not be opened, provisioned or migrated. Never falls back to another
 * database; the request fails and the next one retries.
 */
public class SpokeUnavailableException extends RuntimeException {

  private final TenantId tenantId;

  public SpokeUnavailableException(TenantId tenantId, String message, Throwable cause) {
    super(message, cause);
    this.tenantId = tenantId;
  }

  public TenantId tenantId() {
    return tenantId;
  }
}
