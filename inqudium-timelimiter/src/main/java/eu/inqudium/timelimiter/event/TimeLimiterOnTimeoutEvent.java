package eu.inqudium.timelimiter.event;

import java.time.Duration;
import java.time.Instant;

/**
 * Emitted when the time limiter fires because the caller's wait time exceeded
 * the configured timeout.
 *
 * @since 0.1.0
 */
public class TimeLimiterOnTimeoutEvent extends TimeLimiterEvent {
    private final Duration configuredDuration;

    public TimeLimiterOnTimeoutEvent(String callId, String elementName,
                                      Duration configuredDuration, Instant timestamp) {
        super(callId, elementName, timestamp);
        this.configuredDuration = configuredDuration;
    }

    public Duration getConfiguredDuration() { return configuredDuration; }
}
