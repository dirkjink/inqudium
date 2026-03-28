/**
 * Backoff strategies for retry delay computation.
 *
 * <p>All strategies implement the {@code BackoffStrategy} functional interface:
 * {@code computeDelay(attemptNumber, initialInterval) → Duration}. They are pure
 * functions with no side effects and no threading (ADR-018).
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@code FixedBackoff} — constant delay: {@code delay = initialInterval}.</li>
 *   <li>{@code ExponentialBackoff} — doubling delay: {@code delay = initialInterval × multiplier^(attempt-1)}.
 *       Default multiplier is 2.0.</li>
 *   <li>{@code RandomizedBackoff} — jitter decorator wrapping any strategy. Three algorithms:
 *       <ul>
 *         <li><em>Full jitter</em>: {@code random(0, baseDelay)}. Maximum spread.</li>
 *         <li><em>Equal jitter</em> (recommended default): {@code baseDelay/2 + random(0, baseDelay/2)}.
 *             Guaranteed minimum delay of half the backoff.</li>
 *         <li><em>Decorrelated jitter</em>: {@code min(cap, random(base, previousDelay × 3))}.
 *             Stateful — each instance tracks the previous delay. Best for preventing
 *             retry storms (per AWS analysis).</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>The {@code maxInterval} cap from {@code RetryConfig} is applied by the caller
 * ({@code RetryBehavior}), not by the strategies themselves.
 *
 * @see eu.inqudium.core.retry.RetryConfig
 */
package eu.inqudium.core.retry.backoff;
