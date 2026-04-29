package eu.inqudium.config.runtime;

import eu.inqudium.config.lifecycle.InternalMutabilityCheck;
import eu.inqudium.config.lifecycle.LifecycleAware;
import eu.inqudium.config.lifecycle.ListenerRegistry;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.core.event.InqEventPublisher;

/**
 * Paradigm-agnostic read surface for a live bulkhead.
 *
 * <p>Every paradigm-specific bulkhead handle ({@link ImperativeBulkhead}, and — in later phases —
 * {@code ReactiveBulkhead} and friends) extends this interface. The handle exposes only read
 * accessors and listener-registration; paradigm-specific {@code execute} signatures live on the
 * subinterface because they differ in shape (synchronous return vs.
 * {@code Mono}/{@code Flux}/{@code suspend fun}).
 *
 * <p>The {@link LifecycleAware}, {@link ListenerRegistry}, and {@link InternalMutabilityCheck}
 * super-interfaces are paradigm-agnostic by design and are reused unchanged here. Together they
 * give the update dispatcher everything it needs to route a patch through ADR-028's veto chain
 * without ever importing a paradigm module.
 *
 * @param <P> the paradigm tag.
 */
public interface BulkheadHandle<P extends ParadigmTag>
        extends LifecycleAware,
        ListenerRegistry<BulkheadSnapshot>,
        InternalMutabilityCheck<BulkheadSnapshot> {

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

    /**
     * @return the per-component event publisher this bulkhead uses. Per ADR-030 every live
     *         component owns its own publisher so subscribers can register directly against
     *         this component's events without filtering by element name. The publisher's
     *         identity (element name and element type) matches this handle's
     *         {@link #name()} and the bulkhead element type.
     */
    InqEventPublisher eventPublisher();
}
