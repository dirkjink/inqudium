package eu.inqudium.config.spi;

import eu.inqudium.config.patch.BulkheadPatch;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Accumulated patches for one paradigm section, handed to a {@link ParadigmProvider} at
 * runtime build time.
 *
 * <p>The DSL traversal collects patches by component type, in registration order. Each
 * paradigm provider receives this bundle and materializes the corresponding paradigm-specific
 * components from the patches.
 *
 * <p>Phase&nbsp;1 carries only bulkhead patches because bulkhead is the only component
 * implemented today. As circuit breaker, retry, and time limiter join the architecture in
 * phase&nbsp;3, this class grows additional maps — one per component type. The structural shape
 * of one map per component type is intentional: providers can iterate per type without
 * filtering a heterogeneous list.
 */
public final class ParadigmSectionPatches {

    private final Map<String, BulkheadPatch> bulkheadPatches;

    /**
     * @param bulkheadPatches name-keyed map of bulkhead patches; never null. Order is preserved
     *                        in the defensive copy.
     */
    public ParadigmSectionPatches(Map<String, BulkheadPatch> bulkheadPatches) {
        Objects.requireNonNull(bulkheadPatches, "bulkheadPatches");
        this.bulkheadPatches = new LinkedHashMap<>(bulkheadPatches);
    }

    /**
     * @return an unmodifiable view of the bulkhead patches in registration order.
     */
    public Map<String, BulkheadPatch> bulkheadPatches() {
        return java.util.Collections.unmodifiableMap(bulkheadPatches);
    }

    /**
     * @return {@code true} if this section accumulated no patches at all. Providers may use
     *         this to skip materialization entirely.
     */
    public boolean isEmpty() {
        return bulkheadPatches.isEmpty();
    }
}
