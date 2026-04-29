package eu.inqudium.config.spi;

import eu.inqudium.config.patch.BulkheadPatch;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Accumulated patches for one paradigm section, handed to a {@link ParadigmProvider} at
 * runtime build time.
 *
 * <p>The DSL traversal collects component operations by component type, in registration order:
 * configuration patches (component will be added or its snapshot patched) and structural
 * removals (component will be torn down and pulled from its paradigm map). The DSL guarantees
 * mutual exclusivity per name within one traversal — last writer wins, so a name appears either
 * in {@link #bulkheadPatches()} or in {@link #bulkheadRemovals()}, never in both.
 *
 * <p>Each paradigm provider receives this bundle and materializes the corresponding paradigm-
 * specific components from the patches; the per-paradigm container is responsible for honoring
 * removals against existing components.
 *
 * <p>The class currently carries only bulkhead operations because bulkhead is the only
 * component type implemented. As additional component types (circuit breaker, retry, time
 * limiter, ...) join the architecture, this class grows additional maps and removal-name sets
 * — one pair per component type. The structural shape of one map plus one removal-name set per
 * component type is intentional: providers can iterate per type without filtering a
 * heterogeneous list.
 */
public final class ParadigmSectionPatches {

    private final Map<String, BulkheadPatch> bulkheadPatches;
    private final Set<String> bulkheadRemovals;

    /**
     * Convenience constructor for the patches-only case (initial-config build, where removals
     * make no sense).
     *
     * @param bulkheadPatches name-keyed map of bulkhead patches; never null.
     */
    public ParadigmSectionPatches(Map<String, BulkheadPatch> bulkheadPatches) {
        this(bulkheadPatches, Set.of());
    }

    /**
     * @param bulkheadPatches  name-keyed map of bulkhead patches; never null. Order is preserved
     *                         in the defensive copy.
     * @param bulkheadRemovals names of bulkheads to remove from the runtime; never null.
     *                         Insertion order is preserved.
     */
    public ParadigmSectionPatches(
            Map<String, BulkheadPatch> bulkheadPatches,
            Set<String> bulkheadRemovals) {
        Objects.requireNonNull(bulkheadPatches, "bulkheadPatches");
        Objects.requireNonNull(bulkheadRemovals, "bulkheadRemovals");
        this.bulkheadPatches = new LinkedHashMap<>(bulkheadPatches);
        this.bulkheadRemovals = new LinkedHashSet<>(bulkheadRemovals);
    }

    /**
     * @return an unmodifiable view of the bulkhead patches in registration order.
     */
    public Map<String, BulkheadPatch> bulkheadPatches() {
        return Collections.unmodifiableMap(bulkheadPatches);
    }

    /**
     * @return an unmodifiable view of the bulkhead names slated for removal, in declaration
     *         order. The DSL ensures none of these names also appears in
     *         {@link #bulkheadPatches()}.
     */
    public Set<String> bulkheadRemovals() {
        return Collections.unmodifiableSet(bulkheadRemovals);
    }

    /**
     * @return {@code true} if this section accumulated no operations at all. Providers may use
     *         this to skip materialization entirely.
     */
    public boolean isEmpty() {
        return bulkheadPatches.isEmpty() && bulkheadRemovals.isEmpty();
    }
}
