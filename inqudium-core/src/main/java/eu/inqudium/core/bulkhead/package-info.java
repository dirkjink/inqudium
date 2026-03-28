/**
 * Bulkhead contracts, configuration, and semaphore-based concurrency isolation.
 *
 * <p>Limits the number of concurrent calls to a downstream service, preventing a
 * slow service from consuming all resources in the calling application. Uses semaphore
 * isolation (a concurrency counter) — not thread-pool isolation — because virtual
 * threads (Java 21+) make dedicated thread pools unnecessary (ADR-020).
 *
 * <h2>Key types</h2>
 * <ul>
 *   <li>{@code BulkheadConfig} — immutable configuration: maximum concurrent calls
 *       ({@code maxConcurrentCalls}) and wait timeout ({@code maxWaitDuration}).</li>
 *   <li>{@code BulkheadBehavior} — contract with acquire/release lifecycle:
 *       {@code tryAcquire(state, config) → BulkheadResult} and
 *       {@code release(state) → BulkheadState}. A permit must be released exactly
 *       once per successful acquire, in a {@code finally} block.</li>
 *   <li>{@code BulkheadState} — record: current number of concurrent calls.</li>
 *   <li>{@code BulkheadResult} — record: permitted (boolean) and updated state.</li>
 * </ul>
 *
 * <h2>Acquire/release contract</h2>
 * <p>Unlike other elements, the bulkhead holds a permit for the duration of the call.
 * Every paradigm must guarantee release via its native finally mechanism:
 * {@code try/finally} (imperative), {@code doFinally} (Reactor/RxJava),
 * coroutine {@code try/finally} (Kotlin). Permit leakage is a fatal correctness bug.
 *
 * @see eu.inqudium.core.exception.InqBulkheadFullException
 */
package eu.inqudium.core.bulkhead;
