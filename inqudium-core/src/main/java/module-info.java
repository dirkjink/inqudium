/**
 * Inqudium Core — contracts, configurations, pure algorithms, events, and SPIs
 * that all paradigm modules implement against.
 *
 * <p>This module contains no threading primitives, no paradigm-specific code, and
 * no external dependencies beyond the JDK. Every type in this module is safe to
 * use from imperative Java, Kotlin Coroutines, Project Reactor, and RxJava 3.
 *
 * <h2>Exported packages</h2>
 * <ul>
 *   <li>{@code eu.inqudium.core} — base interfaces ({@code InqElement}, {@code InqConfig}, {@code InqRegistry}, {@code InqClock})</li>
 *   <li>{@code eu.inqudium.core.circuitbreaker} — circuit breaker config, behavior contract, sliding window algorithms</li>
 *   <li>{@code eu.inqudium.core.retry} — retry config and behavior contract</li>
 *   <li>{@code eu.inqudium.core.retry.backoff} — backoff strategies (fixed, exponential, jitter)</li>
 *   <li>{@code eu.inqudium.core.ratelimiter} — rate limiter config, token bucket behavior</li>
 *   <li>{@code eu.inqudium.core.bulkhead} — bulkhead config, semaphore-based behavior</li>
 *   <li>{@code eu.inqudium.core.timelimiter} — time limiter config, timeout profile (RSS calculation)</li>
 *   <li>{@code eu.inqudium.core.cache} — cache config (placeholder for Phase 2)</li>
 *   <li>{@code eu.inqudium.core.event} — event system: publisher, consumer, exporter SPI</li>
 *   <li>{@code eu.inqudium.core.context} — context propagation SPI (capture/restore/enrich)</li>
 *   <li>{@code eu.inqudium.core.compatibility} — behavioral change flags for safe upgrades</li>
 *   <li>{@code eu.inqudium.core.exception} — exception hierarchy and cause-chain navigation</li>
 *   <li>{@code eu.inqudium.core.pipeline} — pipeline composition API and predefined orderings</li>
 * </ul>
 *
 * <h2>Service Provider Interfaces (SPI)</h2>
 * <p>Three SPIs are discoverable via {@link java.util.ServiceLoader}. All follow the
 * conventions defined in ADR-014: lazy discovery, Comparable ordering, error isolation,
 * singleton lifecycle.
 *
 * @see <a href="https://inqudium.io">Inqudium project</a>
 */
module inqudium.core {

    // ── Exports: all public packages ──

    exports eu.inqudium.core;
    exports eu.inqudium.core.circuitbreaker;
    exports eu.inqudium.core.retry;
    exports eu.inqudium.core.retry.backoff;
    exports eu.inqudium.core.ratelimiter;
    exports eu.inqudium.core.bulkhead;
    exports eu.inqudium.core.timelimiter;
    exports eu.inqudium.core.cache;
    exports eu.inqudium.core.event;
    exports eu.inqudium.core.context;
    exports eu.inqudium.core.compatibility;
    exports eu.inqudium.core.exception;
    exports eu.inqudium.core.pipeline;

    // ── ServiceLoader SPI declarations ──

    uses eu.inqudium.core.context.InqContextPropagator;
    uses eu.inqudium.core.compatibility.InqCompatibilityOptions;
    uses eu.inqudium.core.event.InqEventExporter;
}
