/**
 * Imperative lifecycle scaffolding — base class and phase contracts for cold/hot components.
 *
 * <p>This package implements the imperative variant of the per-paradigm lifecycle pattern
 * specified in ADR-029. {@link eu.inqudium.imperative.lifecycle.ImperativeLifecyclePhasedComponent
 * ImperativeLifecyclePhasedComponent} owns the lifecycle-stable resources of a component (name,
 * live container, event publisher, listener list) and orchestrates the cold-to-hot transition
 * through an {@link java.util.concurrent.atomic.AtomicReference AtomicReference} of the current
 * phase. Subclasses provide their hot phase via {@code createHotPhase()}; the base class handles
 * the CAS, the {@code ComponentBecameHotEvent} publication, and the optional
 * {@link eu.inqudium.config.lifecycle.PostCommitInitializable PostCommitInitializable} hook.
 *
 * <p>Per the architectural rule from ADR-029 — inheritance for structural commonality across
 * components within a paradigm, composition for behavioural variation within a component — the
 * lifecycle scaffolding is inherited and the per-component hot-phase logic (strategies, counters,
 * subscriptions) is composed inside the hot phase class.
 */
package eu.inqudium.imperative.lifecycle;
