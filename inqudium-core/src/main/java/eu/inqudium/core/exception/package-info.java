/**
 * Exception base class and cause-chain navigation utility.
 *
 * <p>Inqudium throws its own exceptions <em>only</em> when a resilience element
 * actively intervenes (circuit breaker open, rate limit exhausted, bulkhead full).
 * When the downstream call fails, the original exception propagates unchanged (ADR-009).
 *
 * <h2>Package contents</h2>
 * <ul>
 *   <li>{@link InqException} — abstract base carrying {@code code}, {@code elementName},
 *       and {@code InqElementType}.</li>
 *   <li>{@link InqFailure} — cause-chain navigation utility with fluent API.</li>
 * </ul>
 *
 * <h2>Hierarchy</h2>
 * <p>Concrete exception subclasses live in their respective element packages:
 * <pre>
 * RuntimeException
 * ├── InqRuntimeException                                    — this package (checked exception wrapper)
 * └── InqException (abstract)                                — this package (active intervention)
 *     ├── InqCallNotPermittedException   (INQ-CB-001)        — eu.inqudium.core.circuitbreaker
 *     ├── InqRequestNotPermittedException (INQ-RL-001)       — eu.inqudium.core.ratelimiter
 *     ├── InqBulkheadFullException       (INQ-BH-001)        — eu.inqudium.core.bulkhead
 *     ├── InqTimeLimitExceededException  (INQ-TL-001)        — eu.inqudium.core.timelimiter
 *     └── InqRetryExhaustedException     (INQ-RT-001)        — eu.inqudium.core.retry
 * </pre>
 *
 * <h2>Cause-chain navigation</h2>
 * <p>{@code InqFailure.find(exception)} traverses the entire cause chain — handling
 * {@code ExecutionException}, {@code InvocationTargetException},
 * {@code UndeclaredThrowableException}, and circular references — and returns
 * the first {@code InqException} found:
 * <pre>
 * InqFailure.find(exception)
 *     .ifCircuitBreakerOpen(info -&gt; ...)
 *     .ifRateLimited(info -&gt; ...)
 *     .ifBulkheadFull(info -&gt; ...)
 *     .ifTimeLimitExceeded(info -&gt; ...)
 *     .ifRetryExhausted(info -&gt; ...)
 *     .orElseThrow();
 * </pre>
 *
 * @see eu.inqudium.core.InqElementType
 */
package eu.inqudium.core.exception;
