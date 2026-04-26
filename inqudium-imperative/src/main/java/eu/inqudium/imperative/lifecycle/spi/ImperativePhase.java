package eu.inqudium.imperative.lifecycle.spi;

import eu.inqudium.core.pipeline.InternalExecutor;

/**
 * The internal phase contract used by {@code ImperativeLifecyclePhasedComponent}.
 *
 * <p>The cold phase delegates to a freshly constructed hot phase after a successful CAS; the hot
 * phase runs the component-specific execute logic directly. Concrete components implement this
 * interface in their hot-phase classes (e.g. {@code BulkheadHotPhase}) and pair it with
 * {@link HotPhaseMarker} so the lifecycle base class can detect the hot state.
 */
public interface ImperativePhase {

    /**
     * @param chainId  the chain identifier of the call.
     * @param callId   the call identifier.
     * @param argument the argument flowing through the chain.
     * @param next     the next executor in the chain.
     * @param <A>      the argument type.
     * @param <R>      the return type.
     * @return the value produced by the chain.
     */
    <A, R> R execute(long chainId, long callId, A argument, InternalExecutor<A, R> next);
}
