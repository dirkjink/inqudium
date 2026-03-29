package eu.inqudium.circuitbreaker;

import eu.inqudium.circuitbreaker.internal.CircuitBreakerStateMachine;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.circuitbreaker.CircuitBreakerConfig;
import eu.inqudium.core.circuitbreaker.CircuitBreakerState;
import eu.inqudium.core.circuitbreaker.WindowSnapshot;
import eu.inqudium.core.event.InqEventPublisher;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Imperative circuit breaker — the primary API for decorating synchronous calls
 * with cascading failure protection.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var cb = CircuitBreaker.of("paymentService", CircuitBreakerConfig.builder()
 *     .failureRateThreshold(50)
 *     .slidingWindowSize(10)
 *     .build());
 *
 * Supplier<Payment> resilient = cb.decorateSupplier(() -> paymentService.charge(order));
 * Payment result = resilient.get();
 * }</pre>
 *
 * <p>Thread-safe via {@link java.util.concurrent.locks.ReentrantLock} — virtual-thread
 * safe, no carrier-thread pinning (ADR-008).
 *
 * @since 0.1.0
 */
public interface CircuitBreaker extends InqDecorator {

    /**
     * Creates a new circuit breaker with the given name and configuration.
     *
     * @param name   the instance name
     * @param config the configuration
     * @return a new circuit breaker
     */
    static CircuitBreaker of(String name, CircuitBreakerConfig config) {
        return new CircuitBreakerStateMachine(name, config);
    }

    /**
     * Creates a new circuit breaker with default configuration.
     *
     * @param name the instance name
     * @return a new circuit breaker with defaults
     */
    static CircuitBreaker ofDefaults(String name) {
        return new CircuitBreakerStateMachine(name, CircuitBreakerConfig.ofDefaults());
    }

    /**
     * Returns the current state of the circuit breaker.
     *
     * @return CLOSED, OPEN, or HALF_OPEN
     */
    CircuitBreakerState getState();

    /**
     * Returns the current sliding window snapshot.
     *
     * @return the snapshot with failure rate, slow call rate, and counts
     */
    WindowSnapshot getSnapshot();

    /**
     * Returns the configuration.
     *
     * @return the immutable config
     */
    CircuitBreakerConfig getConfig();

    /**
     * Manually transitions the circuit breaker to CLOSED state and resets the window.
     */
    void transitionToClosedState();

    /**
     * Manually forces the circuit breaker to OPEN state.
     */
    void transitionToOpenState();

    /**
     * Manually transitions the circuit breaker to HALF_OPEN state.
     */
    void transitionToHalfOpenState();

    /**
     * Resets the circuit breaker to CLOSED state with a fresh sliding window.
     */
    void reset();

    /**
     * Decorates a supplier with circuit breaker protection.
     *
     * @param supplier the supplier to decorate
     * @param <T>      the result type
     * @return a decorated supplier that applies circuit breaker logic
     */
    <T> Supplier<T> decorateSupplier(Supplier<T> supplier);

    /**

    /**
     * Decorates a callable with circuit breaker protection.
     *
     * @param callable the callable to decorate
     * @param <T>      the result type
     * @return a decorated supplier (checked exceptions are wrapped in RuntimeException)
     */
    <T> Supplier<T> decorateCallable(Callable<T> callable);

    /**
     * Decorates a runnable with circuit breaker protection.
     *
     * @param runnable the runnable to decorate
     * @return a decorated runnable
     */
    Runnable decorateRunnable(Runnable runnable);

    /**
     * Executes the supplier with circuit breaker protection.
     *
     * @param supplier the supplier to execute
     * @param <T>      the result type
     * @return the result
     */
    default <T> T executeSupplier(Supplier<T> supplier) {
        return decorateSupplier(supplier).get();
    }

    /**
     * Executes the runnable with circuit breaker protection.
     *
     * @param runnable the runnable to execute
     */
    default void executeRunnable(Runnable runnable) {
        decorateRunnable(runnable).run();
    }

    @Override
    default InqElementType getElementType() {
        return InqElementType.CIRCUIT_BREAKER;
    }
}
