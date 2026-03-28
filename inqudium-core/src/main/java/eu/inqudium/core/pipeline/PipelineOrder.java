package eu.inqudium.core.pipeline;

import eu.inqudium.core.InqElementType;

import java.util.*;

/**
 * Predefined pipeline element orderings (ADR-017).
 *
 * <p>Determines the position of each resilience element in the decoration chain.
 * Elements are sorted according to the selected order — the order in which
 * {@code shield()} is called on the pipeline builder is irrelevant when a
 * predefined order is active.
 *
 * <h2>Available orderings</h2>
 * <ul>
 *   <li>{@link #INQUDIUM} — canonical: Cache → TimeLimiter → RateLimiter → Bulkhead → CircuitBreaker → Retry</li>
 *   <li>{@link #RESILIENCE4J} — compatible: Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead</li>
 *   <li>{@link #custom(InqElementType...)} — fully custom order</li>
 * </ul>
 *
 * @since 0.1.0
 */
public final class PipelineOrder {

    private final String name;
    private final List<InqElementType> order;

    private PipelineOrder(String name, List<InqElementType> order) {
        this.name = name;
        this.order = List.copyOf(order);
    }

    /**
     * Inqudium canonical order (ADR-017).
     *
     * <p>Cache → TimeLimiter → RateLimiter → Bulkhead → CircuitBreaker → Retry.
     * Outer elements fire first, inner elements are closest to the protected call.
     *
     * <p>Key properties: TimeLimiter outside Retry (bounds total time), CircuitBreaker
     * outside Retry (sees each attempt individually for fast failure detection),
     * RateLimiter outside CircuitBreaker (rate limit is a global constraint).
     */
    public static final PipelineOrder INQUDIUM = new PipelineOrder("INQUDIUM", List.of(
            InqElementType.CACHE,
            InqElementType.TIME_LIMITER,
            InqElementType.RATE_LIMITER,
            InqElementType.BULKHEAD,
            InqElementType.CIRCUIT_BREAKER,
            InqElementType.RETRY
    ));

    /**
     * Resilience4J compatible order.
     *
     * <p>Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead.
     * Matches R4J's documented aspect ordering for behavioral parity during
     * migration (ADR-006).
     *
     * <p>Key differences from {@link #INQUDIUM}: Retry is outermost (retries the entire
     * pipeline), TimeLimiter is inside (bounds each attempt, not total time).
     */
    public static final PipelineOrder RESILIENCE4J = new PipelineOrder("RESILIENCE4J", List.of(
            InqElementType.RETRY,
            InqElementType.CIRCUIT_BREAKER,
            InqElementType.RATE_LIMITER,
            InqElementType.TIME_LIMITER,
            InqElementType.BULKHEAD,
            InqElementType.CACHE
    ));

    /**
     * Creates a custom ordering from an explicit element type sequence.
     *
     * @param order the element types in desired order (outermost first)
     * @return a custom pipeline order
     */
    public static PipelineOrder custom(InqElementType... order) {
        return new PipelineOrder("CUSTOM", List.of(order));
    }

    /**
     * Returns the position of the given element type in this ordering.
     *
     * <p>Elements not present in the ordering return {@link Integer#MAX_VALUE},
     * placing them at the end (innermost).
     *
     * @param type the element type
     * @return the position (0 = outermost)
     */
    public int positionOf(InqElementType type) {
        int index = order.indexOf(type);
        return index >= 0 ? index : Integer.MAX_VALUE;
    }

    /**
     * Returns a comparator that sorts elements according to this ordering.
     *
     * @return a comparator for element types
     */
    public Comparator<InqElementType> comparator() {
        return Comparator.comparingInt(this::positionOf);
    }

    /**
     * Returns the name of this ordering.
     *
     * @return "INQUDIUM", "RESILIENCE4J", or "CUSTOM"
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the ordered list of element types.
     *
     * @return unmodifiable list, outermost first
     */
    public List<InqElementType> getOrder() {
        return order;
    }

    @Override
    public String toString() {
        return "PipelineOrder{" + name + ": " + order + '}';
    }
}
