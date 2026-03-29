package eu.inqudium.timelimiter.event;

import java.time.Duration;
import java.time.Instant;

/**
 * Emitted when the call fails with an exception before the timeout fires.
 *
 * @since 0.1.0
 */
public class TimeLimiterOnErrorEvent extends TimeLimiterEvent {
    private final Duration duration;
    private final Throwable throwable;

    public TimeLimiterOnErrorEvent(String callId, String elementName,
                                    Duration duration, Throwable throwable, Instant timestamp) {
        super(callId, elementName, timestamp);
        this.duration = duration;
        this.throwable = throwable;
    }

    public Duration getDuration() { return duration; }
    public Throwable getThrowable() { return throwable; }
}
