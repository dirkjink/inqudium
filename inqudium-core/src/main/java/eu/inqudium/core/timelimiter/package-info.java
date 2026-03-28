/**
 * Time limiter configuration and timeout derivation utilities.
 *
 * <p>The time limiter bounds the <em>caller's wait time</em>, not the operation's
 * execution time. It never interrupts threads — orphaned operations continue to
 * completion and are handled via the {@code OrphanedCallHandler} callback (ADR-010).
 *
 * <h2>Key types</h2>
 * <ul>
 *   <li>{@code TimeLimiterConfig} — immutable configuration: timeout duration,
 *       orphaned call handlers ({@code onOrphanedResult}, {@code onOrphanedError}).</li>
 *   <li>{@code InqTimeoutProfile} — central timeout derivation tool. Takes HTTP client
 *       timeouts as input and computes the TimeLimiter timeout and Circuit Breaker
 *       {@code slowCallDurationThreshold} using either RSS (Root Sum of Squares) or
 *       worst-case addition (ADR-012).</li>
 *   <li>{@code TimeoutCalculation} — enum: {@code RSS}, {@code WORST_CASE}.</li>
 * </ul>
 *
 * <h2>Timeout hierarchy</h2>
 * <p>The recommended ordering is:
 * {@code connectTimeout < responseTimeout < TimeLimiter timeout ≤ slowCallDurationThreshold}.
 * The HTTP client timeouts are the fast specialists; the TimeLimiter is the safety net.
 * {@code InqTimeoutProfile} derives consistent values from a single source of truth.
 *
 * <p>Note: the behavioral contract for time limiting (how to wait, how to cancel)
 * lives in the paradigm modules, not in core. The imperative TimeLimiter uses
 * {@code CompletionStage} exclusively — no synchronous {@code Supplier} decoration.
 *
 * @see InqTimeLimitExceededException
 */
package eu.inqudium.core.timelimiter;
