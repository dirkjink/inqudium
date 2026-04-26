package eu.inqudium.config.lifecycle;

/**
 * Read-only view of a component's current {@link LifecycleState}.
 *
 * <p>Implemented by every live component (typically through the per-paradigm lifecycle base class
 * specified in ADR-029). The method is the single inspection point used by the update dispatcher
 * to decide whether a patch routes through the veto chain (HOT) or applies directly (COLD).
 *
 * <p>Implementations must guarantee that the returned value reflects a consistent observation —
 * but the framework does not require strict linearizability across multiple calls. A component
 * that observes {@code COLD} and then immediately {@code HOT} mid-update is the expected race; the
 * dispatcher handles it via the snapshot-replacement CAS.
 */
public interface LifecycleAware {

    /**
     * @return the component's current lifecycle phase.
     */
    LifecycleState lifecycleState();
}
