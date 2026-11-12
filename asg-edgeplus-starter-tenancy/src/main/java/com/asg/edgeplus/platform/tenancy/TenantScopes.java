package com.asg.edgeplus.platform.tenancy;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The scope bound to the current thread.
 *
 * <p>Web requests are bound by {@link TenantScopeFilter}. Anything with no request — a Kafka
 * consumer, a scheduled job — enters a scope itself with {@link #runIn} or {@link #callIn}, which is
 * the same explicit act a request performs by naming its tenant.
 *
 * <p>A plain ThreadLocal rather than a ScopedValue: Spring's servlet stack, Hikari and Hibernate
 * all run work on the calling thread, and the filter must bind across the servlet boundary.
 */
public final class TenantScopes {

  private static final ThreadLocal<TenantScope> CURRENT = new ThreadLocal<>();

  /** The bound scope, or empty when nothing has said which firm (or the platform) this is. */
  public static Optional<TenantScope> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /**
   * Runs {@code work} in {@code scope} and restores whatever was bound before. Restores rather than
   * clears so that platform work inside firm work (or the reverse) does not drop the outer scope.
   */
  public static <T> T callIn(TenantScope scope, Supplier<T> work) {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(work, "work");
    TenantScope previous = CURRENT.get();
    CURRENT.set(scope);
    try {
      return work.get();
    } finally {
      restore(previous);
    }
  }

  public static void runIn(TenantScope scope, Runnable work) {
    Objects.requireNonNull(work, "work");
    callIn(
        scope,
        () -> {
          work.run();
          return null;
        });
  }

  static void bind(TenantScope scope) {
    CURRENT.set(scope);
  }

  /** Must be called at the end of every request: pooled threads outlive the request. */
  static void clear() {
    CURRENT.remove();
  }

  private static void restore(TenantScope previous) {
    if (previous == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(previous);
    }
  }

  private TenantScopes() {}
}
