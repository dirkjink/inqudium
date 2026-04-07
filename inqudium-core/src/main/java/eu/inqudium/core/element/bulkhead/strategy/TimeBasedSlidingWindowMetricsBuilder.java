package eu.inqudium.core.element.bulkhead.strategy;

import eu.inqudium.core.element.circuitbreaker.metrics.TimeBasedSlidingWindowMetrics;
import java.time.Instant;

public class TimeBasedSlidingWindowMetricsBuilder {
    private int windowSizeInSeconds = 60;

    public TimeBasedSlidingWindowMetricsBuilder windowSizeInSeconds(int seconds) {
        this.windowSizeInSeconds = seconds;
        return this;
    }

    public TimeBasedSlidingWindowMetrics build(Instant now) {
        return TimeBasedSlidingWindowMetrics.initial(windowSizeInSeconds, now);
    }
}