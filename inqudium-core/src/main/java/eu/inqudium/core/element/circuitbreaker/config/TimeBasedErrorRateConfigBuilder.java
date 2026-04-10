package eu.inqudium.core.element.circuitbreaker.config;

import eu.inqudium.core.config.ExtensionBuilder;

public class TimeBasedErrorRateConfigBuilder extends ExtensionBuilder<TimeBasedErrorRateConfig> {
    private Double failureRatePercent;
    private Integer windowSizeInSeconds;
    private Integer minimumNumberOfCalls;

    public TimeBasedErrorRateConfigBuilder failureRatePercent(double failureRatePercent) {
        this.failureRatePercent = failureRatePercent;
        return this;
    }

    public TimeBasedErrorRateConfigBuilder windowSizeInSeconds(int seconds) {
        this.windowSizeInSeconds = seconds;
        return this;
    }

    public TimeBasedErrorRateConfigBuilder minimumNumberOfCalls(int calls) {
        this.minimumNumberOfCalls = calls;
        return this;
    }

    /**
     * Reacts quickly to outages within a short 10-second observation window.
     */
    public TimeBasedErrorRateConfigBuilder protective() {
        this.windowSizeInSeconds = 10;
        this.minimumNumberOfCalls = 5;
        return this;
    }

    /**
     * Industry standard 1-minute window for stable error rate calculation.
     */
    public TimeBasedErrorRateConfigBuilder balanced() {
        this.windowSizeInSeconds = 60;
        this.minimumNumberOfCalls = 20;
        return this;
    }

    /**
     * Long 5-minute window to ignore temporary network flakiness.
     */
    public TimeBasedErrorRateConfigBuilder permissive() {
        this.windowSizeInSeconds = 300;
        this.minimumNumberOfCalls = 100;
        return this;
    }

    public TimeBasedErrorRateConfig build() {
        if (windowSizeInSeconds == null || minimumNumberOfCalls == null || failureRatePercent == null) {
            balanced();
        }
        return new TimeBasedErrorRateConfig(failureRatePercent, windowSizeInSeconds, minimumNumberOfCalls);
    }
}