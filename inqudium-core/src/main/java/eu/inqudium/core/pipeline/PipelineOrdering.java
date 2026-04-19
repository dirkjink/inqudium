package eu.inqudium.core.pipeline;

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
 *     case BULKHEAD        -> 100;
 *     case CIRCUIT_BREAKER -> 200;
 *     case RATE_LIMITER    -> 300;
 *     case RETRY           -> 400;
 *     case TIME_LIMITER    -> 500;
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
     * Returns the standard inqudium ordering (ADR-017), delegating to
     * {@link InqElementType#defaultPipelineOrder()}.
     *
     * <pre>
     *   TIME_LIMITER (100) → TRAFFIC_SHAPER (200) → RATE_LIMITER (300)
     *     → BULKHEAD (400) → CIRCUIT_BREAKER (500) → RETRY (600)
     * </pre>
     *
     * <p>Cache is not part of the pipeline — it is a separate interceptor
     * that runs before the pipeline (see ADR-024).</p>
     *
     * <p>Rationale: the time-limiter bounds total caller wait time including
     * shaping delays and retries; the traffic-shaper smooths bursts before
     * rate tokens are consumed; the circuit-breaker sees each retry attempt
     * individually.</p>
     *
     * @return the standard ordering
     */
    static PipelineOrdering standard() {
        return InqElementType::defaultPipelineOrder;
    }

    // ======================== Built-in profiles ========================

    /**
     * Returns an ordering compatible with Resilience4j's default decorator
     * sequence (ADR-017).
     *
     * <pre>
     *   RETRY (100) → CIRCUIT_BREAKER (200) → TRAFFIC_SHAPER (300)
     *     → RATE_LIMITER (400) → TIME_LIMITER (500) → BULKHEAD (600)
     * </pre>
     *
     * <p>In this model, retry is outermost (each retry sees a fresh
     * circuit-breaker/rate-limiter/bulkhead attempt), and the bulkhead
     * is innermost (concurrency is limited per-attempt, not per-retry-cycle).
     * The time-limiter bounds each individual attempt, not total time.
     * Traffic-shaper sits before rate-limiter in both profiles.</p>
     *
     * @return the Resilience4j-compatible ordering
     */
    static PipelineOrdering resilience4j() {
        return Profiles.RESILIENCE4J;
    }

    /**
     * Creates a custom ordering from an explicit type-to-order mapping.
     *
     * <p>Types not present in the map fall back to
     * {@link InqElementType#defaultPipelineOrder()}.</p>
     *
     * <p>Pipelines built with custom orderings are
     * {@linkplain PipelineValidator validated} automatically at build time
     * to detect common anti-patterns. Warnings are logged but do not
     * prevent the pipeline from being created.</p>
     *
     * <p>Equivalent to {@code of(orders, true)}.</p>
     *
     * @param orders the type-to-order mapping
     * @return a custom ordering with build-time validation enabled
     * @throws NullPointerException if orders is null
     * @see #of(Map, boolean)
     */
    static PipelineOrdering of(Map<InqElementType, Integer> orders) {
        return of(orders, true);
    }

    /**
     * Creates a custom ordering from an explicit type-to-order mapping,
     * with explicit control over build-time validation.
     *
     * <p>Types not present in the map fall back to
     * {@link InqElementType#defaultPipelineOrder()}.</p>
     *
     * @param orders                the type-to-order mapping
     * @param shouldValidateOnBuild whether {@link InqPipeline.Builder#build()}
     *                              should run anti-pattern validation
     * @return a custom ordering
     * @throws NullPointerException if orders is null
     */
    static PipelineOrdering of(Map<InqElementType, Integer> orders,
                               boolean shouldValidateOnBuild) {
        Objects.requireNonNull(orders, "Orders map must not be null");
        EnumMap<InqElementType, Integer> snapshot = new EnumMap<>(orders);
        return new PipelineOrdering() {
            @Override
            public int orderFor(InqElementType type) {
                return snapshot.getOrDefault(type, type.defaultPipelineOrder());
            }

            @Override
            public boolean shouldValidateOnBuild() {
                return shouldValidateOnBuild;
            }

            @Override
            public String toString() {
                return "PipelineOrdering.of(" + snapshot + ")";
            }
        };
    }

    // ======================== Custom profiles ========================

    /**
     * Returns the pipeline order for the given element type.
     *
     * <p>Lower values produce outermost layers (higher precedence).</p>
     *
     * @param type the element type
     * @return the pipeline priority
     */
    int orderFor(InqElementType type);

    /**
     * Returns {@code true} if the pipeline should run anti-pattern
     * validation automatically at build time.
     *
     * <p>Standard and Resilience4J orderings return {@code false} because
     * their element order is well-defined and intentional.
     * {@link #of(Map)} returns {@code true} because user-defined
     * orderings are most likely to contain mistakes.</p>
     *
     * @return {@code true} if build-time validation is recommended
     */
    default boolean shouldValidateOnBuild() {
        return false;
    }

    // ======================== Internal ========================

    /**
     * Holder for pre-built profile instances — avoids re-creating EnumMaps
     * on every call.
     */
    final class Profiles {
        static final PipelineOrdering RESILIENCE4J;

        static {
            EnumMap<InqElementType, Integer> r4j = new EnumMap<>(InqElementType.class);
            r4j.put(InqElementType.RETRY, 100);
            r4j.put(InqElementType.CIRCUIT_BREAKER, 200);
            r4j.put(InqElementType.TRAFFIC_SHAPER, 300);
            r4j.put(InqElementType.RATE_LIMITER, 400);
            r4j.put(InqElementType.TIME_LIMITER, 500);
            r4j.put(InqElementType.BULKHEAD, 600);
            // Built-in profile — validation disabled (ordering is intentional)
            RESILIENCE4J = of(r4j, false);
        }

        private Profiles() {
        }
    }
}
