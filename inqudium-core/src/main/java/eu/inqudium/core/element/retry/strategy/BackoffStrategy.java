package eu.inqudium.core.element.retry.strategy;

import java.time.Duration;
import java.util.function.IntFunction;

/**
 * Strategy for computing the wait duration between retry attempts.
 *
 * <p>All built-in implementations are immutable value objects. Stateless strategies
 * override {@link #computeDelay(int)}; stateful strategies (e.g.,
 * {@link DecorrelatedJitterBackoffStrategy}) override
 * {@link #computeDelay(int, Duration)} to incorporate the previous delay.
 *
 * <p>This interface is intentionally <strong>not sealed</strong> — applications
 * can provide custom implementations directly (e.g., for reading retry-after
 * headers, external configuration, or adaptive algorithms). The built-in
 * {@link CustomBackoffStrategy} wrapper is a convenience for simple lambda-based strategies.
 *
 * <h2>Built-in strategies</h2>
 * <table>
 *   <tr><th>Strategy</th><th>Growth</th><th>Use case</th></tr>
 *   <tr><td>{@link FixedBackoffStrategy}</td><td>Constant</td><td>Simple retries with predictable timing</td></tr>
 *   <tr><td>{@link LinearBackoffStrategy}</td><td>Additive</td><td>Moderate backoff for short-lived outages</td></tr>
 *   <tr><td>{@link FibonacciBackoffStrategy}</td><td>~φ^n ≈ 1.618^n</td><td>Gentler than exponential, faster than linear</td></tr>
 *   <tr><td>{@link ExponentialBackoffStrategy}</td><td>Multiplicative</td><td>Standard backoff for network retries</td></tr>
 *   <tr><td>{@link ExponentialWithJitterBackoffStrategy}</td><td>Randomised exponential</td><td>Distributed systems (avoids thundering herd)</td></tr>
 *   <tr><td>{@link DecorrelatedJitterBackoffStrategy}</td><td>Stateful random</td><td>AWS-recommended; best herd avoidance</td></tr>
 *   <tr><td>{@link NoWaitBackoffStrategy}</td><td>Zero</td><td>Immediate retries (tests, local ops)</td></tr>
 *   <tr><td>{@link CustomBackoffStrategy}</td><td>User-defined</td><td>Full control without subclassing</td></tr>
 * </table>
 */
public interface BackoffStrategy {

    // ======================== Factory methods ========================

    static BackoffStrategy fixedDelay(Duration delay) {
        return new FixedBackoffStrategy(delay);
    }

    static BackoffStrategy linear(Duration initialDelay, Duration increment) {
        return new LinearBackoffStrategy(initialDelay, increment, Duration.ofSeconds(30));
    }

    static BackoffStrategy linear(Duration initialDelay, Duration increment, Duration maxDelay) {
        return new LinearBackoffStrategy(initialDelay, increment, maxDelay);
    }

    static BackoffStrategy fibonacci(Duration initialDelay) {
        return new FibonacciBackoffStrategy(initialDelay, Duration.ofSeconds(30));
    }

    static BackoffStrategy fibonacci(Duration initialDelay, Duration maxDelay) {
        return new FibonacciBackoffStrategy(initialDelay, maxDelay);
    }

    static BackoffStrategy exponential(Duration initialDelay) {
        return new ExponentialBackoffStrategy(initialDelay, 2.0, Duration.ofSeconds(30));
    }

    static BackoffStrategy exponential(Duration initialDelay, double multiplier, Duration maxDelay) {
        return new ExponentialBackoffStrategy(initialDelay, multiplier, maxDelay);
    }

    static BackoffStrategy exponentialWithJitter(Duration initialDelay) {
        return new ExponentialWithJitterBackoffStrategy(initialDelay, 2.0, Duration.ofSeconds(30));
    }

    static BackoffStrategy exponentialWithJitter(Duration initialDelay, double multiplier, Duration maxDelay) {
        return new ExponentialWithJitterBackoffStrategy(initialDelay, multiplier, maxDelay);
    }

    static BackoffStrategy decorrelatedJitter(Duration initialDelay) {
        return new DecorrelatedJitterBackoffStrategy(initialDelay, Duration.ofSeconds(30));
    }

    static BackoffStrategy decorrelatedJitter(Duration initialDelay, Duration maxDelay) {
        return new DecorrelatedJitterBackoffStrategy(initialDelay, maxDelay);
    }

    static BackoffStrategy noWait() {
        return new NoWaitBackoffStrategy();
    }

    static BackoffStrategy custom(IntFunction<Duration> delayFunction) {
        return new CustomBackoffStrategy(delayFunction);
    }

    // ======================== Core interface ========================

    /**
     * Computes the delay before the given retry attempt.
     *
     * <p>Stateless strategies should override this method. The default
     * implementation of {@link #computeDelay(int, Duration)} delegates here,
     * ignoring the previous delay.
     *
     * @param attemptIndex zero-based retry index (0 = first retry after initial failure)
     * @return the duration to wait
     */
    Duration computeDelay(int attemptIndex);

    /**
     * Computes the delay before the given retry attempt, with access to
     * the previous delay for stateful strategies.
     *
     * <p>The default implementation ignores {@code previousDelay} and delegates
     * to {@link #computeDelay(int)}. Stateful strategies (e.g.,
     * {@link DecorrelatedJitterBackoffStrategy}) override this method.
     *
     * <p>The retry core always calls this two-argument form, passing the
     * delay from the previous retry cycle (or {@link Duration#ZERO} for
     * the first retry).
     *
     * @param attemptIndex  zero-based retry index
     * @param previousDelay the delay used for the previous retry, or {@link Duration#ZERO}
     * @return the duration to wait
     */
    default Duration computeDelay(int attemptIndex, Duration previousDelay) {
        return computeDelay(attemptIndex);
    }

    // ======================== Implementations ========================

}
