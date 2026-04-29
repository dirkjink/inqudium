package eu.inqudium.config.runtime;

import java.util.Optional;
import java.util.Set;

/**
 * Imperative paradigm container — the runtime-side registry of imperative components.
 *
 * <p>Returned by {@link InqRuntime#imperative()}. Provides typed access to the imperative
 * components configured for the runtime: bulkheads today, {@code ImperativeCircuitBreaker},
 * {@code ImperativeRetry}, and {@code ImperativeTimeLimiter} in later phases.
 *
 * <p>The interface returns paradigm-agnostic handles
 * ({@link BulkheadHandle BulkheadHandle&lt;ImperativeTag&gt;}, etc.) so {@code inqudium-config}
 * never references the concrete classes from {@code inqudium-imperative}. The paradigm-module-
 * specific implementation lives in {@code inqudium-imperative} as {@code DefaultImperative}.
 *
 * <p>Callers that need the typed pipeline surface ({@code decorateFunction(...)} et al.) can cast
 * the returned handle to the concrete {@code InqBulkhead<A, R>} from {@code inqudium-imperative}.
 * Read-only diagnostics (snapshot, permits, listener registration) are available directly on the
 * handle.
 */
public interface Imperative extends ParadigmContainer<ImperativeTag> {

    /**
     * @param name the bulkhead's name.
     * @return the bulkhead handle.
     * @throws IllegalArgumentException if no bulkhead with this name is configured.
     */
    BulkheadHandle<ImperativeTag> bulkhead(String name);

    /**
     * @param name the bulkhead's name.
     * @return the bulkhead handle if one is configured, otherwise empty.
     */
    Optional<BulkheadHandle<ImperativeTag>> findBulkhead(String name);

    /**
     * @return the names of every configured bulkhead, in registration order.
     */
    Set<String> bulkheadNames();
}
