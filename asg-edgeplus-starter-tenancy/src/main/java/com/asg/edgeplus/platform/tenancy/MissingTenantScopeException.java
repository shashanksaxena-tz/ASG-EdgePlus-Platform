package com.asg.edgeplus.platform.tenancy;

/**
 * Database access was attempted with no tenant scope bound. Fail-closed: the alternative is to
 * guess a database, and a wrong guess serves one firm's data to another.
 */
public class MissingTenantScopeException extends IllegalStateException {

  public MissingTenantScopeException() {
    super(
        "database access with no tenant scope bound. A request needs a tenant from the gateway"
            + " (X-Tenant-Id) or an explicit platform scope; a job or consumer must use"
            + " TenantScopes.runIn(...). If this is Liquibase or Hibernate at startup, set"
            + " spring.liquibase.enabled=false and spring.jpa.properties.hibernate.boot"
            + ".allow_jdbc_metadata_access=false: this starter migrates each firm's schema itself.");
  }
}
