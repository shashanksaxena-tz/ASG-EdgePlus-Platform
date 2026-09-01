/**
 * Domain primitives — value objects shared across every service's domain layer.
 *
 * <p>Strict rules for everything in this package:
 *
 * <ul>
 *   <li>Pure Java; no Spring, no JPA, no Jackson, no infrastructure imports.
 *   <li>Immutable; records preferred; defensive copies on collection inputs.
 *   <li>Construction validates invariants and throws {@link IllegalArgumentException}.
 *   <li>{@code equals}/{@code hashCode} based on identity-defining fields only.
 *   <li>Public API documented; missing Javadoc fails the build.
 * </ul>
 *
 * <p>These are <em>technical</em> value objects, not bounded-context concepts, which is why sharing
 * them does not violate {@code java-architecture} SKILL §7.7. Constitution §4.4 mandates {@code
 * Money} platform-wide and ArchUnit enforces the no-{@code double} rule in every service — a rule
 * every service must obey identically cannot sensibly be reimplemented per service.
 */
package com.asg.edgeplus.platform.domain.primitives;
