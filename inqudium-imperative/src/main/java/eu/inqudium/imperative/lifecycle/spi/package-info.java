/**
 * SPI types for component authors implementing imperative resilience components.
 *
 * <p>Application code does not interact with the types in this package. They exist as the contract
 * between {@link eu.inqudium.imperative.lifecycle.ImperativeLifecyclePhasedComponent} and the
 * concrete component classes that extend it ({@code InqBulkhead}, {@code InqCircuitBreaker}, ...).
 * The base class returns these types from protected accessors and accepts them from the abstract
 * {@code createHotPhase()} factory method; component implementers are the sole consumers.
 *
 * <p>Locating the types in a separate sub-package signals their SPI status by convention. The
 * containing {@code lifecycle} package owns the application-facing surface; this {@code spi}
 * package owns the contracts that paradigm-internal components use.
 */
package eu.inqudium.imperative.lifecycle.spi;
