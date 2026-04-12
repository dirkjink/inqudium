package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.element.InqElementType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Maps {@link InqElementType} to pipeline order values, defining the nesting
 * of resilience layers.
 *
 * <p>A {@code PipelineOrdering} is a pure function from element type to
 * integer priority. Lower values produce outermost layers. The framework
 * ships two built-in profiles accessible via {@link #standard()} and
 * {@link #resilience4j()}; custom orderings can be created via
 * {@link #of(Map)} or as a lambda.</p>
 *
 * <h3>Usage with ElementLayerProvider</h3>
 * <pre>{@code
 * // Standard inqudium ordering (default — no argument needed)
 * new ElementLayerProvider(bulkhead)
 *
 * // Resilience4j-compatible ordering
 * PipelineOrdering r4j = PipelineOrdering.resilience4j();
 * new ElementLayerProvider(bulkhead, r4j)
 * new ElementLayerProvider(retry, r4j)
 *
 * // Custom ordering via lambda
 * PipelineOrdering custom = type -> switch (type) {
 *     case BULKHEAD        -> 500;
 *     case CIRCUIT_BREAKER -> 400;
 *     case RATE_LIMITER    -> 300;
 *     case RETRY           -> 200;
 *     case TIME_LIMITER    -> 100;
 *     default              -> type.defaultPipelineOrder();
 * };
 * }</pre>
 *
 * <h3>Thread safety</h3>
 * <p>All built-in orderings are immutable and safe for concurrent use.</p>
 *
 * @since 0.8.0
 */
@FunctionalInterface
public interface PipelineOrdering {

    /**
     * Returns the pipeline order for the given element type.
     *
     * <p>Lower values produce outermost layers (higher precedence).</p>
     *
     * @param type the element type
     * @return the pipeline priority
     */
    int orderFor(InqElementType type);

    // ======================== Built-in profiles ========================

    /**
     * Returns the standard inqudium ordering, delegating to
     * {@link InqElementType#defaultPipelineOrder()}.
     *
     * <pre>
     *   CACHE (50) → BULKHEAD (100) → CIRCUIT_BREAKER (200)
     *     → RATE_LIMITER (300) → RETRY (400) → TIME_LIMITER (500)
     * </pre>
     *
     * <p>Rationale: the bulkhead rejects early before expensive work;
     * the circuit-breaker fails fast before retries consume budget;
     * each retry attempt is individually time-limited.</p>
     *
     * @return the standard ordering
     */
    static PipelineOrdering standard() {
        return InqElementType::defaultPipelineOrder;
    }

    /**
     * Returns an ordering compatible with Resilience4j's default decorator
     * sequence.
     *
     * <pre>
     *   RETRY (100) → CIRCUIT_BREAKER (200) → RATE_LIMITER (300)
     *     → TIME_LIMITER (400) → BULKHEAD (500) → CACHE (600)
     * </pre>
     *
     * <p>In this model, retry is outermost (each retry sees a fresh
     * circuit-breaker/rate-limiter/bulkhead attempt), and the bulkhead
     * is innermost (concurrency is limited per-attempt, not per-retry-cycle).</p>
     *
     * @return the Resilience4j-compatible ordering
     */
    static PipelineOrdering resilience4j() {
        return Profiles.RESILIENCE4J;
    }

    // ======================== Custom profiles ========================

    /**
     * Creates a custom ordering from an explicit type-to-order mapping.
     *
     * <p>Types not present in the map fall back to
     * {@link InqElementType#defaultPipelineOrder()}.</p>
     *
     * @param orders the type-to-order mapping
     * @return a custom ordering
     * @throws NullPointerException if orders is null
     */
    static PipelineOrdering of(Map<InqElementType, Integer> orders) {
        Objects.requireNonNull(orders, "Orders map must not be null");
        EnumMap<InqElementType, Integer> snapshot = new EnumMap<>(orders);
        return type -> snapshot.getOrDefault(type, type.defaultPipelineOrder());
    }

    // ======================== Internal ========================

    /**
     * Holder for pre-built profile instances — avoids re-creating EnumMaps
     * on every call.
     */
    final class Profiles {
        private Profiles() {}

        static final PipelineOrdering RESILIENCE4J;

        static {
            EnumMap<InqElementType, Integer> r4j = new EnumMap<>(InqElementType.class);
            r4j.put(InqElementType.RETRY,           100);
            r4j.put(InqElementType.CIRCUIT_BREAKER,  200);
            r4j.put(InqElementType.RATE_LIMITER,     300);
            r4j.put(InqElementType.TIME_LIMITER,     400);
            r4j.put(InqElementType.BULKHEAD,         500);
            r4j.put(InqElementType.CACHE,            600);
            RESILIENCE4J = of(r4j);
        }
    }
}
