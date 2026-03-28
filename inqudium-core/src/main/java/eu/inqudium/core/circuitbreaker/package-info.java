/**
 * Circuit breaker contracts, configuration, and sliding window algorithms.
 *
 * <p>This package defines the shared abstractions that all paradigm-specific circuit
 * breaker implementations build upon. The algorithms are pure — no locks, no atomics,
 * no blocking. The paradigm module provides synchronization.
 *
 * <h2>Key types</h2>
 * <ul>
 *   <li>{@code CircuitBreakerState} — enum: {@code CLOSED}, {@code OPEN}, {@code HALF_OPEN}.</li>
 *   <li>{@code CircuitBreakerConfig} — immutable configuration: failure rate threshold,
 *       slow call threshold, sliding window type/size, wait duration in open state,
 *       minimum number of calls, and compatibility flags (ADR-013).</li>
 *   <li>{@code CircuitBreakerBehavior} — behavioral contract: decides state transitions
 *       based on {@code WindowSnapshot} (ADR-005).</li>
 *   <li>{@code SlidingWindow} — contract: {@code record(CallOutcome) → WindowSnapshot}.
 *       Two implementations: {@code CountBasedSlidingWindow} (circular buffer, O(1)) and
 *       {@code TimeBasedSlidingWindow} (time buckets, {@code InqClock}-driven) (ADR-016).</li>
 *   <li>{@code CallOutcome} — record: success/failure, duration in nanos, timestamp.</li>
 *   <li>{@code WindowSnapshot} — record: failure rate, slow call rate, call counts,
 *       and {@code hasMinimumCalls()} for sample size validation.</li>
 * </ul>
 *
 * <h2>Purity guarantee</h2>
 * <p>No type in this package calls {@code Instant.now()}, {@code System.currentTimeMillis()},
 * or any blocking operation. Time is provided by {@link eu.inqudium.core.InqClock}.
 *
 * @see eu.inqudium.core.InqClock
 * @see eu.inqudium.core.compatibility.InqCompatibility
 */
package eu.inqudium.core.circuitbreaker;
