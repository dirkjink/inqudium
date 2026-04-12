package eu.inqudium.aspect.pipeline.integration;

/**
 * Order service — demonstrates a business service whose methods are
 * intercepted by the {@link ResilienceAspect} via AspectJ CTW.
 *
 * <p>After weaving, calls to {@code @Resilient} methods are routed through
 * the pipeline: AUTHORIZATION → LOGGING → TIMING → original method body.</p>
 */
public class OrderService {

    /**
     * Places an order — annotated with {@code @Resilient}, woven by CTW.
     *
     * @param item     the item to order
     * @param quantity the quantity
     * @return a confirmation string
     */
    @Resilient
    public String placeOrder(String item, int quantity) {
        return "Ordered " + quantity + "x " + item;
    }

    /**
     * Cancels an order — annotated with {@code @Resilient}, woven by CTW.
     *
     * @param orderId the order ID to cancel
     * @return a cancellation confirmation
     */
    @Resilient
    public String cancelOrder(String orderId) {
        return "Cancelled " + orderId;
    }

    /**
     * Looks up order status — NOT annotated, executes directly without pipeline.
     *
     * @param orderId the order ID to look up
     * @return the status string
     */
    public String getStatus(String orderId) {
        return "Status of " + orderId + ": shipped";
    }

    /**
     * Validates an order — annotated with {@code @Resilient}, but throws
     * to verify exception transport through the woven pipeline.
     *
     * @param orderId the order ID to validate
     * @return never returns
     * @throws IllegalArgumentException always
     */
    @Resilient
    public String validateOrder(String orderId) {
        throw new IllegalArgumentException("Invalid order: " + orderId);
    }
}
