package eu.inqudium.config.lifecycle;

/**
 * The lifecycle phase of a live component.
 *
 * <p>Every component starts in {@link #COLD} and transitions monotonically to {@link #HOT} on its
 * first {@code execute} call (or the paradigm-specific equivalent — first subscription on a
 * returned {@code Mono}/{@code Flux}, first {@code suspend} call, ...). The transition is
 * automatic, observable, and one-directional. There is no way to force or revert it from outside
 * the component.
 *
 * <p>The cold/hot distinction governs how runtime updates are routed: cold patches apply
 * directly, hot patches pass through the veto chain (ADR-028). The implementation pattern that
 * realizes the transition is specified in ADR-029.
 */
public enum LifecycleState {

    /**
     * The component is configured but has not yet served any calls. Updates apply directly,
     * skipping the veto chain.
     */
    COLD,

    /**
     * The component has begun serving calls. Updates pass through the veto chain (registered
     * listeners and the component-internal mutability check) before being applied.
     */
    HOT
}
