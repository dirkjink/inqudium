package eu.inqudium.imperative.lifecycle.spi;

import eu.inqudium.core.pipeline.LayerAction;

/**
 * The internal phase contract used by {@code ImperativeLifecyclePhasedComponent}.
 *
 * <p>The cold phase delegates to a freshly constructed hot phase after a successful CAS; the hot
 * phase runs the component-specific execute logic directly. Concrete components implement this
 * interface in their hot-phase classes (e.g. {@code BulkheadHotPhase}) and pair it with
 * {@link HotPhaseMarker} so the lifecycle base class can detect the hot state.
 *
 * <p>Structurally, a phase is a pipeline layer that bears lifecycle state: the inheritance from
 * {@link LayerAction} aligns the phase's execute signature with every other layer in the chain
 * so a phase can sit transparently between layer-aware consumers (per ADR-033).
 *
 * @param <A> the argument type flowing through the chain.
 * @param <R> the return type flowing back through the chain.
 */
public interface ImperativePhase<A, R> extends LayerAction<A, R> {
}
