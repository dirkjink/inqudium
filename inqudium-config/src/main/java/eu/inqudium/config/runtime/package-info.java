/**
 * Runtime container — {@code InqRuntime}, paradigm containers, and the cross-paradigm read view.
 *
 * <p>This package owns the top-level interfaces of ADR-026: {@code InqRuntime},
 * {@code ParadigmContainer&lt;P&gt;}, the paradigm-specific subinterfaces ({@code Imperative},
 * {@code Reactive}, {@code RxJava3}, {@code Coroutines}), {@code InqConfigView}, and the
 * {@code Inqudium#configure()} entry point. Implementations of paradigm containers live in their
 * respective paradigm modules and are bridged to this module via the
 * {@link eu.inqudium.config.spi.ParadigmProvider} SPI.
 *
 * <p>The runtime is built once via the DSL, mutated atomically per component via
 * {@code runtime.update(...)}, observable via {@code runtime.config()}, validated on demand via
 * {@code runtime.diagnose()}, and shut down via {@code runtime.close()}. There is no second source
 * of truth and no separate per-component registry.
 */
package eu.inqudium.config.runtime;
