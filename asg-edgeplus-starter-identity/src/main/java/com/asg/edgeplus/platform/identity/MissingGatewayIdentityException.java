package com.asg.edgeplus.platform.identity;

import java.util.UUID;

/**
 * The request did not carry a usable gateway identity.
 *
 * <p>In a deployed environment this means the request did not come through the API gateway — the
 * gateway authenticates every non-permit-all path and always sets the identity headers, and its
 * {@code InboundHeaderScrubFilter} removes any a client tried to forge. So a missing header is not
 * a malformed request, it is a request that reached the service by a route that skipped
 * authentication entirely. Failing closed is the only safe response.
 *
 * <p>Carries a stable {@code code} for the RFC 7807 {@code code} field (Constitution §4.5).
 */
public class MissingGatewayIdentityException extends RuntimeException {

  private final String code;

  public static MissingGatewayIdentityException missingHeader(String headerName) {
    return new MissingGatewayIdentityException(
        "identity.gateway.headerMissing",
        "Request is missing the '"
            + headerName
            + "' header set by the API gateway; it did not pass through the gateway.");
  }

  public static MissingGatewayIdentityException malformedHeader(String headerName, String reason) {
    return new MissingGatewayIdentityException(
        "identity.gateway.headerMalformed",
        "Header '" + headerName + "' set by the API gateway is malformed: " + reason);
  }

  public static MissingGatewayIdentityException noActiveTenant(UUID userId) {
    return new MissingGatewayIdentityException(
        "identity.tenant.absent",
        "Caller " + userId + " has no active tenant, but this operation requires one.");
  }

  private MissingGatewayIdentityException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
