package eu.inqudium.config.snapshot;

/**
 * Sealed marker for the immutable snapshot record of a single component.
 *
 * <p>Every concrete snapshot ({@link BulkheadSnapshot}, and — once added in later phases —
 * {@code CircuitBreakerSnapshot}, {@code RetrySnapshot}, {@code TimeLimiterSnapshot}, ...) is a
 * {@code record} that implements this interface. Snapshots carry the full configuration state of
 * one component, are validated by their compact constructors (ADR-027 class&nbsp;2), and are
 * paradigm-agnostic — the {@code ParadigmTag} of a live component is carried by the handle and
 * the {@link eu.inqudium.config.live.LiveContainer LiveContainer}, not by the snapshot.
 *
 * <p>The interface is sealed so that pattern matching across all snapshot kinds is checked for
 * exhaustiveness by the compiler (ADR-026). New component types must be added to the
 * {@code permits} clause as they are introduced.
 */
public sealed interface ComponentSnapshot permits BulkheadSnapshot {

    /**
     * @return the component's stable name, which together with its paradigm forms the lookup key
     *         in the runtime registry.
     */
    String name();
}
