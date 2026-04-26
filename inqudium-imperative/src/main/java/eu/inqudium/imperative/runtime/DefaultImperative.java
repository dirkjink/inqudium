package eu.inqudium.imperative.runtime;

import eu.inqudium.config.runtime.Imperative;
import eu.inqudium.config.runtime.ImperativeBulkhead;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.ParadigmTag;
import eu.inqudium.imperative.bulkhead.InqBulkhead;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Default implementation of the {@link Imperative} paradigm container.
 *
 * <p>Holds the imperative bulkheads materialized at runtime build time, keyed by name in
 * registration order. Lookups are an {@code O(1)} map read; the container is read-only after
 * construction in phase&nbsp;1.7-C — phase&nbsp;1.7-D extends it with {@code add} and
 * {@code patch} entry points so {@code runtime.update(...)} can introduce new components.
 */
public final class DefaultImperative implements Imperative {

    private final Map<String, InqBulkhead> bulkheads;

    /**
     * @param bulkheads name-keyed bulkhead components; defensively copied to a
     *                  {@code LinkedHashMap} so registration order is preserved.
     */
    public DefaultImperative(Map<String, InqBulkhead> bulkheads) {
        Objects.requireNonNull(bulkheads, "bulkheads");
        this.bulkheads = new LinkedHashMap<>(bulkheads);
    }

    @Override
    public ParadigmTag paradigm() {
        return ImperativeTag.INSTANCE;
    }

    @Override
    public ImperativeBulkhead bulkhead(String name) {
        InqBulkhead b = bulkheads.get(name);
        if (b == null) {
            throw new IllegalArgumentException(
                    "No bulkhead named '" + name + "'. Available: " + bulkheads.keySet());
        }
        return b;
    }

    @Override
    public Optional<ImperativeBulkhead> findBulkhead(String name) {
        return Optional.ofNullable(bulkheads.get(name));
    }

    @Override
    public Set<String> bulkheadNames() {
        return Set.copyOf(bulkheads.keySet());
    }
}
