package eu.inqudium.config.runtime;

import java.util.Optional;
import java.util.Set;

/**
 * Imperative paradigm container — the runtime-side registry of imperative components.
 *
 * <p>Returned by {@link InqRuntime#imperative()}. Provides typed access to the imperative
 * components configured for the runtime: {@link ImperativeBulkhead}s today,
 * {@code ImperativeCircuitBreaker}, {@code ImperativeRetry}, and {@code ImperativeTimeLimiter}
 * in later phases.
 *
 * <p>The interface returns paradigm-agnostic handles ({@link ImperativeBulkhead}, etc.) so
 * {@code inqudium-config} never references the concrete classes from {@code inqudium-imperative}.
 * The paradigm-module-specific implementation lives in {@code inqudium-imperative} as
 * {@code DefaultImperative}.
 */
public interface Imperative extends ParadigmContainer<ImperativeTag> {

    /**
     * @param name the bulkhead's name.
     * @return the bulkhead handle.
     * @throws IllegalArgumentException if no bulkhead with this name is configured.
     */
    ImperativeBulkhead bulkhead(String name);

    /**
     * @param name the bulkhead's name.
     * @return the bulkhead handle if one is configured, otherwise empty.
     */
    Optional<ImperativeBulkhead> findBulkhead(String name);

    /**
     * @return the names of every configured bulkhead, in registration order.
     */
    Set<String> bulkheadNames();
}
