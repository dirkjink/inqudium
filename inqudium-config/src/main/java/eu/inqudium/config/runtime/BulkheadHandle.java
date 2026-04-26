package eu.inqudium.config.runtime;

import eu.inqudium.config.lifecycle.LifecycleAware;
import eu.inqudium.config.lifecycle.ListenerRegistry;
import eu.inqudium.config.snapshot.BulkheadSnapshot;

/**
 * Paradigm-agnostic read surface for a live bulkhead.
 *
 * <p>Every paradigm-specific bulkhead handle ({@link ImperativeBulkhead}, and — in later phases —
 * {@code ReactiveBulkhead} and friends) extends this interface. The handle exposes only read
 * accessors and listener-registration; paradigm-specific {@code execute} signatures live on the
 * subinterface because they differ in shape (synchronous return vs.
 * {@code Mono}/{@code Flux}/{@code suspend fun}).
 *
 * <p>The {@link LifecycleAware} and {@link ListenerRegistry} super-interfaces are paradigm-
 * agnostic by design and are reused unchanged here.
 *
 * @param <P> the paradigm tag.
 */
public interface BulkheadHandle<P extends ParadigmTag>
        extends LifecycleAware, ListenerRegistry<BulkheadSnapshot> {

    /**
     * @return the bulkhead's stable name.
     */
    String name();

    /**
     * @return the bulkhead's current snapshot, read directly from the underlying live container.
     */
    BulkheadSnapshot snapshot();

    /**
     * @return the number of permits currently available. When the bulkhead is hot, the value
     *         comes from the live strategy; when cold, it falls back to the snapshot's
     *         {@code maxConcurrentCalls}.
     */
    int availablePermits();

    /**
     * @return the number of permits currently held by in-flight calls. Zero when cold.
     */
    int concurrentCalls();
}
