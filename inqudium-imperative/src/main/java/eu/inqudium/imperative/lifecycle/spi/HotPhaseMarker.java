package eu.inqudium.imperative.lifecycle.spi;

/**
 * Marker implemented by every hot phase so that
 * {@code ImperativeLifecyclePhasedComponent#lifecycleState()} can detect the hot state through an
 * {@code instanceof} check without coupling to concrete hot-phase subtypes.
 */
public interface HotPhaseMarker {
}
