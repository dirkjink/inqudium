package eu.inqudium.core.element.fallback;

import java.time.Duration;
import java.time.Instant;

/**
 * Event emitted by the fallback provider for observability.
 *
 * @param name        the fallback provider name
 * @param type        the event type
 * @param handlerName the name of the handler involved ({@code null} for primary events)
 * @param elapsed     elapsed time since the execution started
 * @param failure     the exception that triggered the event ({@code null} for success events)
 * @param timestamp   when the event occurred
 */
public record FallbackEvent(
        String name,
        Type type,
        String handlerName,
        Duration elapsed,
        Throwable failure,
        Instant timestamp
) {

    public static FallbackEvent primaryStarted(String name, Instant now) {
        return new FallbackEvent(name, Type.PRIMARY_STARTED, null, Duration.ZERO, null, now);
    }

    public static FallbackEvent primarySucceeded(String name, Duration elapsed, Instant now) {
        return new FallbackEvent(name, Type.PRIMARY_SUCCEEDED, null, elapsed, null, now);
    }

    public static FallbackEvent primaryFailed(String name, Duration elapsed, Throwable failure, Instant now) {
        return new FallbackEvent(name, Type.PRIMARY_FAILED, null, elapsed, failure, now);
    }

    public static FallbackEvent fallbackInvoked(String name, String handlerName, Duration elapsed, Instant now) {
        return new FallbackEvent(name, Type.FALLBACK_INVOKED, handlerName, elapsed, null, now);
    }

    public static FallbackEvent fallbackRecovered(String name, String handlerName, Duration elapsed, Instant now) {
        return new FallbackEvent(name, Type.FALLBACK_RECOVERED, handlerName, elapsed, null, now);
    }

    public static FallbackEvent fallbackFailed(String name, String handlerName, Duration elapsed,
                                               Throwable failure, Instant now) {
        return new FallbackEvent(name, Type.FALLBACK_FAILED, handlerName, elapsed, failure, now);
    }

    public static FallbackEvent noHandlerMatched(String name, Duration elapsed, Throwable failure, Instant now) {
        return new FallbackEvent(name, Type.NO_HANDLER_MATCHED, null, elapsed, failure, now);
    }

    public static FallbackEvent resultFallbackInvoked(String name, String handlerName, Duration elapsed, Instant now) {
        return new FallbackEvent(name, Type.RESULT_FALLBACK_INVOKED, handlerName, elapsed, null, now);
    }

    public static FallbackEvent resultFallbackRecovered(String name, String handlerName,
                                                        Duration elapsed, Instant now) {
        return new FallbackEvent(name, Type.RESULT_FALLBACK_RECOVERED, handlerName, elapsed, null, now);
    }

    @Override
    public String toString() {
        return "FallbackProvider '%s': %s%s — elapsed %s ms at %s"
                .formatted(name, type,
                        handlerName != null ? " [" + handlerName + "]" : "",
                        elapsed.toMillis(), timestamp);
    }

    public enum Type {
        /**
         * The primary operation started.
         */
        PRIMARY_STARTED,
        /**
         * The primary operation succeeded — no fallback needed.
         */
        PRIMARY_SUCCEEDED,
        /**
         * The primary operation failed; a matching fallback handler was found.
         */
        PRIMARY_FAILED,
        /**
         * A fallback handler is being invoked.
         */
        FALLBACK_INVOKED,
        /**
         * The fallback handler recovered successfully.
         */
        FALLBACK_RECOVERED,
        /**
         * The fallback handler itself failed.
         */
        FALLBACK_FAILED,
        /**
         * No matching handler was found for the primary exception.
         */
        NO_HANDLER_MATCHED,
        /**
         * A result-based fallback is being invoked.
         */
        RESULT_FALLBACK_INVOKED,
        /**
         * A result-based fallback recovered successfully.
         */
        RESULT_FALLBACK_RECOVERED
    }
}
