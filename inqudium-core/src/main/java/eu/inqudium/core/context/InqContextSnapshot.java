package eu.inqudium.core.context;

/**
 * Opaque snapshot of diagnostic context state captured by an {@link InqContextPropagator}.
 *
 * <p>Implementations carry their own internal state (e.g. a copy of the MDC map,
 * an OpenTelemetry Baggage snapshot). The content is opaque to the core —
 * only the propagator that created it knows how to restore it.
 *
 * @since 0.1.0
 */
public interface InqContextSnapshot {
  // Marker interface — implementations carry their own state
}
