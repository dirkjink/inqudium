package eu.inqudium.core.element.retry.strategy;

import java.time.Duration;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * User-defined delay function for full control without subclassing.
 *
 * <p>The function receives the zero-based attempt index and returns the
 * delay duration. Use this for one-off or application-specific backoff
 * patterns that don't warrant a dedicated strategy class.
 *
 * <p>Example:
 * <pre>{@code
 *   // Delay from a lookup table
 *   Duration[] delays = {Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30)};
 *   BackoffStrategy.custom(i -> delays[Math.min(i, delays.length - 1)]);
 * }</pre>
 *
 * @param delayFunction maps a zero-based attempt index to a delay duration
 */
public record CustomBackoffStrategy(IntFunction<Duration> delayFunction) implements BackoffStrategy {

    public CustomBackoffStrategy {
        Objects.requireNonNull(delayFunction, "delayFunction must not be null");
    }

    @Override
    public Duration computeDelay(int attemptIndex) {
        Duration delay = delayFunction.apply(attemptIndex);
        Objects.requireNonNull(delay,
                "delayFunction must not return null for attemptIndex " + attemptIndex);
        if (delay.isNegative()) {
            throw new IllegalArgumentException(
                    "delayFunction returned negative delay for attemptIndex %d: %s"
                            .formatted(attemptIndex, delay));
        }
        return delay;
    }
}
