package eu.inqudium.config.snapshot;

/**
 * Marker for the immutable snapshot record of a single component.
 *
 * <p>Every concrete snapshot ({@code BulkheadSnapshot}, {@code CircuitBreakerSnapshot}, ...) is a
 * {@code record} that implements this interface. Snapshots carry the full configuration state of
 * one component, are validated by their compact constructors (ADR-027 class&nbsp;2), and are
 * paradigm-agnostic — the {@code ParadigmTag} of a live component is carried by the handle and
 * the {@link eu.inqudium.config.live.LiveContainer LiveContainer}, not by the snapshot.
 *
 * <p>This interface will become {@code sealed} once the first concrete snapshot record exists in
 * step&nbsp;1.4. It is plain at this point only because Java refuses to compile a sealed type with
 * no permitted subtypes; switching it back to {@code sealed} is a follow-up edit, not a design
 * change.
 */
public interface ComponentSnapshot {

    /**
     * @return the component's stable name, which together with its paradigm forms the lookup key
     *         in the runtime registry.
     */
    String name();
}
