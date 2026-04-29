package eu.inqudium.core.event;

/**
 * Handle for cancelling an event subscription.
 *
 * <p>Returned by {@link InqEventPublisher#onEvent} methods. Call {@link #cancel()}
 * to remove the consumer from the publisher:
 * <pre>{@code
 * InqSubscription sub = circuitBreaker.eventPublisher()
 *     .onEvent(CircuitBreakerOnStateTransitionEvent.class, e -> log.info("transition"));
 *
 * // Later — unsubscribe
 * sub.cancel();
 * }</pre>
 *
 * <p>Cancellation is idempotent — calling {@link #cancel()} multiple times has
 * no effect after the first call.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface InqSubscription {

    /**
     * Cancels this subscription, removing the consumer from the publisher.
     *
     * <p>Idempotent — safe to call multiple times.
     */
    void cancel();
}
