package eu.inqudium.timelimiter.event;

import java.time.Duration;
import java.time.Instant;

/**
 * Emitted when the call completes within the timeout.
 *
 * @since 0.1.0
 */
public class TimeLimiterOnSuccessEvent extends TimeLimiterEvent {
    private final Duration duration;

    public TimeLimiterOnSuccessEvent(String callId, String elementName, Duration duration, Instant timestamp) {
        super(callId, elementName, timestamp);
        this.duration = duration;
    }

    public Duration getDuration() { return duration; }
}
