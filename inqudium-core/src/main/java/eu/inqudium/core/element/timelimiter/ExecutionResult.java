package eu.inqudium.core.element.timelimiter;

import java.time.Duration;
import java.util.Objects;

/**
 * The outcome of a time-limited execution.
 *
 * <p>Wraps either a successful value, a failure exception, or a timeout
 * indication, together with the final execution snapshot for timing metadata.
 *
 * @param <T> the result type
 */
public sealed interface ExecutionResult<T> {

    /**
     * Returns the final execution snapshot with timing information.
     */
    ExecutionSnapshot snapshot();

    /**
     * Returns the elapsed duration of the execution.
     */
    default Duration elapsed() {
        ExecutionSnapshot snap = snapshot();
        if (snap.startTime() == null || snap.endTime() == null) {
            return Duration.ZERO;
        }
        return Duration.between(snap.startTime(), snap.endTime());
    }

    /**
     * A successful result.
     */
    record Success<T>(T value, ExecutionSnapshot snapshot) implements ExecutionResult<T> {
        public Success {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
        }
    }

    /**
     * A timeout result — the operation exceeded the configured time limit.
     */
    record Timeout<T>(ExecutionSnapshot snapshot) implements ExecutionResult<T> {
        public Timeout {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
        }
    }

    /**
     * A failure result — the operation threw an exception within the time limit.
     */
    record Failure<T>(Throwable cause, ExecutionSnapshot snapshot) implements ExecutionResult<T> {
        public Failure {
            Objects.requireNonNull(cause, "cause must not be null");
            Objects.requireNonNull(snapshot, "snapshot must not be null");
        }
    }

    /**
     * A cancellation result — the operation was canceled (typically after timeout).
     */
    record Cancelled<T>(ExecutionSnapshot snapshot) implements ExecutionResult<T> {
        public Cancelled {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
        }
    }
}
