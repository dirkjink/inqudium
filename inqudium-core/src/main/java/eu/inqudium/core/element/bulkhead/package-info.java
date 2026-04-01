/**
 * Bulkhead contracts, configuration, and base implementation for concurrency isolation.
 *
 * <p>Limits the number of concurrent calls to a downstream service, preventing a
 * slow service from consuming all resources in the calling application. Uses semaphore
 * isolation (a concurrency counter) — not thread-pool isolation — because virtual
 * threads (Java 21+) make dedicated thread pools unnecessary (ADR-020).
 *
 * <h2>Key types</h2>
 * <ul>
 *   <li>{@link AbstractBulkhead} — base implementation containing all bulkhead logic:
 *       decoration, event publishing, exception handling, and state queries. Paradigm
 *       modules only provide the permit mechanism
 *       ({@link AbstractBulkhead#tryAcquirePermit tryAcquirePermit} /
 *       {@link AbstractBulkhead#releasePermit releasePermit}).</li>
 *   <li>{@link BulkheadConfig} — immutable configuration: maximum concurrent calls
 *       ({@code maxConcurrentCalls}) and wait timeout ({@code maxWaitDuration}).</li>
 *   <li>{@link InqBulkheadFullException} — thrown when no concurrency permits are
 *       available and the wait timeout is exceeded.</li>
 * </ul>
 *
 * <h2>Acquire/release contract</h2>
 * <p>The permit is held for the duration of the call and released in a
 * {@code finally} block within {@link AbstractBulkhead#decorate}. Every paradigm
 * must guarantee release via its native finally mechanism:
 * {@code try/finally} (imperative), {@code doFinally} (Reactor/RxJava),
 * coroutine {@code try/finally} (Kotlin). Permit leakage is a fatal correctness bug.
 *
 * @see AbstractBulkhead
 * @see InqBulkheadFullException
 */
package eu.inqudium.core.element.bulkhead;
