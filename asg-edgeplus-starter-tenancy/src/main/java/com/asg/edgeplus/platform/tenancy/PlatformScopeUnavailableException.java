package com.asg.edgeplus.platform.tenancy;

/**
 * Platform scope was entered but the service has no platform DataSource. It is refused rather than
 * answered from some firm's spoke.
 */
public class PlatformScopeUnavailableException extends IllegalStateException {

  public PlatformScopeUnavailableException() {
    super(
        "platform scope requested but this service has no DataSource bean named"
            + " 'platformDataSource'; refusing to serve platform work from a firm database");
  }
}
