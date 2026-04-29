package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ConfigExtension;

/**
 * Immutable configuration record for {@link eu.inqudium.core.element.circuitbreaker.metrics.TimeBasedErrorRateMetrics}.
 *
 * <p>Holds the parameters for the time-based error rate algorithm, which tracks both
 * successes and failures in 1-second buckets and trips the circuit when the computed
 * failure <em>rate</em> (failures ÷ total calls) exceeds a percentage threshold.
 *
 * <p>Unlike {@link TimeBasedSlidingWindowConfig}, this configuration defines a
 * <em>percentage-based</em> threshold and also tracks success counts, making the
 * trip decision proportional to traffic volume rather than based on an absolute
 * failure count.
 *
 * @param failureRatePercent   the percentage threshold (1–100); the circuit trips when the
 *                             failure rate in the window meets or exceeds this value
 * @param windowSizeInSeconds  the duration of the rolling observation window in seconds
 *                             (also the number of 1-second buckets); must be &gt; 0
 * @param minimumNumberOfCalls the minimum total calls (successes + failures) in the window
 *                             before the rate is evaluated; prevents premature tripping
 *                             during low-traffic periods (must be &gt; 0)
 * @see eu.inqudium.core.element.circuitbreaker.metrics.TimeBasedErrorRateMetrics
 * @see TimeBasedErrorRateConfigBuilder
 */
public record TimeBasedErrorRateConfig(
        double failureRatePercent,
        int windowSizeInSeconds,
        int minimumNumberOfCalls
) implements ConfigExtension<TimeBasedErrorRateConfig> {

    @Override
    public TimeBasedErrorRateConfig self() {
        return this;
    }
}
