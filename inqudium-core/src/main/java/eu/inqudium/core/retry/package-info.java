/**
 * Retry contracts and configuration.
 *
 * <p>Defines the behavioral contract for retry decisions and the configuration
 * parameters that control retry behavior across all paradigms. The actual waiting
 * (sleep, delay, Mono.delay) is a paradigm concern — this package only decides
 * <em>whether</em> to retry and <em>how long</em> to wait.
 *
 * <h2>Key types</h2>
 * <ul>
 *   <li>{@code RetryConfig} — immutable configuration: max attempts, initial interval,
 *       backoff strategy, max interval cap, exception filters ({@code retryOn},
 *       {@code ignoreOn}), and the {@code retryOnInqExceptions} flag which defaults
 *       to {@code false} (ADR-017, ADR-018).</li>
 *   <li>{@code RetryBehavior} — contract: {@code shouldRetry(attemptNumber, exception, config)
 *       → Optional<Duration>}. Returns empty if no retry should be attempted.</li>
 * </ul>
 *
 * <p>Backoff strategies live in the {@link eu.inqudium.core.retry.backoff} sub-package.
 *
 * @see eu.inqudium.core.retry.backoff
 * @see InqRetryExhaustedException
 */
package eu.inqudium.core.retry;
