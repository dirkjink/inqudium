/**
 * Exception hierarchy — minimal, unchecked, with cause-chain navigation.
 *
 * <p>Inqudium throws its own exceptions <em>only</em> when a resilience element
 * actively intervenes (circuit breaker open, rate limit exhausted, bulkhead full).
 * When the downstream call fails, the original exception propagates unchanged (ADR-009).
 *
 * <h2>Hierarchy</h2>
 * <pre>
 * RuntimeException
 * └── InqException (abstract)
 *     ├── InqCallNotPermittedException      — circuit breaker is OPEN
 *     ├── InqRequestNotPermittedException   — rate limiter denied
 *     ├── InqBulkheadFullException          — no concurrency permits
 *     ├── InqTimeLimitExceededException     — caller's wait time exceeded (ADR-010)
 *     └── InqRetryExhaustedException        — all attempts failed, carries last cause
 * </pre>
 *
 * <p>All exceptions carry {@code elementName} and {@code InqElementType} for
 * identification. Subclasses add element-specific context (state, failure rate,
 * attempt count, configured/actual duration).
 *
 * <h2>Cause-chain navigation</h2>
 * <p>{@code InqFailure.find(exception)} traverses the entire cause chain — handling
 * {@code ExecutionException}, {@code InvocationTargetException},
 * {@code UndeclaredThrowableException}, and circular references — and returns
 * the first {@code InqException} found. This decouples the application from
 * exception type matching at the catch-site:
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
