package com.asg.edgeplus.platform.identity;

import java.util.Optional;

/**
 * Thread-bound access to the current {@link CallerContext}, for code that cannot take it as a
 * parameter — an audit interceptor, a JPA auditor-aware, a scheduled job's manufactured context.
 *
 * <p>Prefer declaring {@code CallerContext} as a controller method parameter; it is explicit and
 * trivially testable. Reach for this holder only where the call site genuinely has no seam.
 *
 * <p>Safe under virtual threads: each request runs on its own thread, virtual or platform, so the
 * binding is per-request. {@link GatewayIdentityFilter} always clears it in a {@code finally}, so a
 * pooled platform thread cannot carry one request's identity into the next.
 */
public final class CallerContextHolder {

  private static final ThreadLocal<CallerContext> CURRENT = new ThreadLocal<>();

  /** The caller bound to this thread, if any. */
  public static Optional<CallerContext> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /**
   * The caller bound to this thread.
   *
   * @throws MissingGatewayIdentityException when nothing is bound — a clearer failure than a
   *     {@code NullPointerException} in whatever tried to read it
   */
  public static CallerContext require() {
    CallerContext ctx = CURRENT.get();
    if (ctx == null) {
      throw MissingGatewayIdentityException.missingHeader(GatewayIdentityHeaders.USER_ID);
    }
    return ctx;
  }

  static void bind(CallerContext ctx) {
    CURRENT.set(ctx);
  }

  static void clear() {
    CURRENT.remove();
  }

  private CallerContextHolder() {}
}
