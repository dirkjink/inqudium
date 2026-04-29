/**
 * Bulkhead contracts, exceptions, and supporting subpackages for concurrency isolation
 * in {@code inqudium-core}.
 *
 * <p>A bulkhead limits the number of concurrent calls flowing through a protected
 * downstream so a slow service cannot consume all caller-side resources. Inqudium uses
 * semaphore-based isolation rather than thread-pool isolation because virtual threads
 * make dedicated thread pools unnecessary (ADR-020).
 *
 * <h2>What lives here</h2>
 * <ul>
 *   <li>{@link InqBulkheadFullException} — thrown when a bulkhead rejects a call
 *       because the strategy refused the permit (limit reached, wait timeout, CoDel
 *       drop, etc.). Carries a
 *       {@link eu.inqudium.core.element.bulkhead.strategy.RejectionContext}
 *       captured inside the strategy's decision.</li>
 *   <li>{@link InqBulkheadInterruptedException} — thrown when a thread is interrupted
 *       while waiting for a permit.</li>
 *   <li>{@link BulkheadEventPublishFailureException} — fatal wrapper raised when
 *       publishing a bulkhead event fails after a permit has been acquired.</li>
 *   <li>{@link eu.inqudium.core.element.bulkhead.algo} — limit algorithms (AIMD, Vegas)
 *       feeding the adaptive strategies.</li>
 *   <li>{@link eu.inqudium.core.element.bulkhead.strategy} — strategy contracts and
 *       rejection-context types consumed by the paradigm-side strategy implementations.</li>
 *   <li>{@link eu.inqudium.core.element.bulkhead.event} — event records published on the
 *       bulkhead lifecycle (acquire, release, reject).</li>
 *   <li>{@link eu.inqudium.core.element.bulkhead.config} and
 *       {@link eu.inqudium.core.element.bulkhead.dsl} — legacy configuration and DSL
 *       types kept alive for the legacy {@code Resilience} DSL and the not-yet-migrated
 *       circuit breaker; superseded by {@code eu.inqudium.config.snapshot.BulkheadSnapshot}
 *       and {@code Inqudium.configure()}.</li>
 * </ul>
 *
 * <p>The active permit-management implementations live in the paradigm modules
 * ({@code inqudium-imperative}, {@code inqudium-reactor}, etc.); this package only
 * holds the cross-paradigm contracts and shared exception types.
 */
package eu.inqudium.core.element.bulkhead;
