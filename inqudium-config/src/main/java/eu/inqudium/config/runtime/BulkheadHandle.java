package eu.inqudium.config.runtime;

import eu.inqudium.config.lifecycle.InternalMutabilityCheck;
import eu.inqudium.config.lifecycle.LifecycleAware;
import eu.inqudium.config.lifecycle.ListenerRegistry;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.core.element.InqElement;

/**
 * Paradigm-agnostic read surface for a live bulkhead.
 *
 * <p>Every paradigm-specific bulkhead handle (the imperative {@code InqBulkhead}, and — in later
 * phases — {@code ReactiveBulkhead} and friends) implements this interface, parameterised by the
 * paradigm tag. The handle exposes only read accessors and listener-registration; paradigm-specific
 * {@code execute} signatures live on the concrete component because they differ in shape
 * (synchronous return vs. {@code Mono}/{@code Flux}/{@code suspend fun}).
 *
 * <p>The {@link LifecycleAware}, {@link ListenerRegistry}, and {@link InternalMutabilityCheck}
 * super-interfaces are paradigm-agnostic by design and are reused unchanged here. Together they
 * give the update dispatcher everything it needs to route a patch through ADR-028's veto chain
 * without ever importing a paradigm module.
 *
 * <p>{@link InqElement} contributes the {@code name()}, {@code elementType()}, and
 * {@code eventPublisher()} accessors (ADR-033 Stage 3). Every bulkhead handle's
 * {@link InqElement#elementType() elementType()} returns
 * {@link eu.inqudium.core.element.InqElementType#BULKHEAD} — the constraint is enforced by the
 * implementations, not the type system.
 *
 * @param <P> the paradigm tag.
 */
public interface BulkheadHandle<P extends ParadigmTag>
        extends InqElement,
        LifecycleAware,
        ListenerRegistry<BulkheadSnapshot>,
        InternalMutabilityCheck<BulkheadSnapshot> {

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
