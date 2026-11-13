package com.asg.edgeplus.platform.tenancy;

/** Makes a spoke ready for this service. Idempotent and safe to call concurrently. */
@FunctionalInterface
public interface SpokeProvisioner {

  /** Ensures this service's schema exists in {@code databaseName} and its changelog is applied. */
  void ensureReady(String databaseName);
}
