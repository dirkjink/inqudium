package eu.inqudium.imperative.retry;

import eu.inqudium.core.element.retry.*;
import eu.inqudium.core.element.retry.strategy.BackoffStrategy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe, imperative retry implementation.
 *
 * <p>Each {@link #execute} call creates its own {@link RetrySnapshot} —
 * there is no shared mutable state between executions. Thread safety comes
 * from the immutability of the snapshot and configuration.
 *
 * <p><strong>Clock and backoff behavior (Fix 8):</strong> The backoff delay
 * is computed by the {@link BackoffStrategy} and honoured
 * via {@link LockSupport#parkNanos}. When using an injectable {@link Clock}
 * (e.g., in tests), the remaining duration is calculated as
 * {@code Duration.between(clock.instant(), targetWakeup)}. If the clock
 * immediately advances past the target, the delay is effectively zero.
 * This is intentional — it allows tests to skip real waiting by advancing
 * the clock. For tests that need to verify actual waiting, use a real clock.
 */
public class ImperativeRetry {

    private static final Logger LOG = Logger.getLogger(ImperativeRetry.class.getName());

    private final RetryConfig config;
    private final Clock clock;
    private final List<Consumer<RetryEvent>> eventListeners;
    private final String instanceId;

    public ImperativeRetry(RetryConfig config) {
        this(config, Clock.systemUTC());
    }

    public ImperativeRetry(RetryConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.eventListeners = new CopyOnWriteArrayList<>();
        this.instanceId = UUID.randomUUID().toString();
    }

    // ======================== Callable Execution ========================

    public <T> T execute(Callable<T> callable) throws Exception {
        Objects.requireNonNull(callable, "callable must not be null");

        Instant now = clock.instant();
        RetrySnapshot snapshot = RetryCore.startFirstAttempt(now);
        emitEvent(RetryEvent.attemptStarted(config.name(), 1, snapshot.totalElapsed(now), now));

        while (true) {
            boolean success = false;
            T result = null;
            Throwable attemptFailure = null;

            try {
                result = callable.call();
                success = true;
            } catch (Throwable e) {
                // Fix 5 (original): InterruptedException must bypass the retry chain entirely.
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw (InterruptedException) e;
                }
                attemptFailure = e;
            }

            if (success) {
                RetryDecision resultDecision = RetryCore.evaluateResult(snapshot, config, result);

                switch (resultDecision) {
                    case RetryDecision.Accept accept -> {
                        snapshot = accept.snapshot();
                        Instant completedAt = clock.instant();
                        emitEvent(RetryEvent.attemptSucceeded(
                                config.name(), snapshot.attemptNumber(),
                                snapshot.totalElapsed(completedAt), completedAt));
                        return result;
                    }

                    case RetryDecision.DoRetry doRetry -> {
                        snapshot = doRetry.snapshot();
                        emitResultRetryEvent(snapshot, doRetry);
                    }

                    case RetryDecision.RetriesExhausted exhausted -> {
                        snapshot = exhausted.snapshot();
                        emitExhaustedEvent(snapshot);
                        throw new RetryException(
                                config.name(), instanceId, snapshot.totalAttempts(),
                                snapshot.lastFailure(), snapshot.failures(),
                                exhausted.resultBased());
                    }

                    // Result evaluation never produces DoNotRetry
                    case RetryDecision.DoNotRetry ignored ->
                            throw new IllegalStateException("evaluateResult should not produce DoNotRetry");
                }

            } else {
                RetryDecision decision = RetryCore.evaluateFailure(snapshot, config, attemptFailure);

                switch (decision) {
                    case RetryDecision.DoRetry doRetry -> {
                        // Fix 10 + 11: Update snapshot FIRST, then emit event with correct failure
                        snapshot = doRetry.snapshot();
                        emitExceptionRetryEvent(snapshot, doRetry);
                    }

                    case RetryDecision.DoNotRetry doNotRetry -> {
                        // Fix 10: Update snapshot before emitting event for consistency
                        snapshot = doNotRetry.snapshot();
                        Instant failedAt = clock.instant();
                        emitEvent(RetryEvent.failedNonRetryable(
                                config.name(), snapshot.attemptNumber(),
                                snapshot.totalElapsed(failedAt), doNotRetry.failure(), failedAt));
                        // Transparent propagation — rethrow the original exception
                        if (attemptFailure instanceof Exception ex) throw ex;
                        if (attemptFailure instanceof Error err) throw err;
                        throw new RuntimeException(attemptFailure);
                    }

                    case RetryDecision.RetriesExhausted exhausted -> {
                        snapshot = exhausted.snapshot();
                        emitExhaustedEvent(snapshot);
                        throw new RetryException(
                                config.name(), instanceId, snapshot.totalAttempts(),
                                attemptFailure, snapshot.failures(),
                                exhausted.resultBased());
                    }

                    // Failure evaluation never produces Accept
                    case RetryDecision.Accept ignored ->
                            throw new IllegalStateException("evaluateFailure should not produce Accept");
                }
            }

            // Wait and start next attempt
            if (snapshot.state() == RetryState.WAITING_FOR_RETRY) {
                Duration delay = snapshot.nextRetryDelay();
                if (delay != null && delay.isPositive()) {
                    // Fix 1: parkUntil now throws a real InterruptedException
                    parkUntil(clock.instant().plus(delay));
                }

                Instant retryNow = clock.instant();
                snapshot = RetryCore.startNextAttempt(snapshot, retryNow);
                emitEvent(RetryEvent.attemptStarted(
                        config.name(), snapshot.attemptNumber(),
                        snapshot.totalElapsed(retryNow), retryNow));
            }
        }
    }

    // ======================== Runnable Execution ========================

    /**
     * Fix 2: Direct implementation instead of delegating to the Callable variant.
     * Eliminates the unreachable catch block and ensures InterruptedException
     * from the backoff delay is properly propagated.
     *
     * <p>InterruptedExceptions from the backoff delay propagate as unchecked
     * {@link RuntimeException} wrappers since Runnable execution cannot declare
     * checked exceptions. The interrupt flag remains set for the caller to inspect.
     */
    public void execute(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable must not be null");
        try {
            execute(() -> {
                runnable.run();
                return null;
            });
        } catch (InterruptedException e) {
            // Interrupt flag was already restored in the Callable execute().
            // Wrap as unchecked since execute(Runnable) cannot throw checked exceptions.
            throw new RuntimeException(e);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Executes with a fallback that activates when <em>this</em> retry instance
     * exhausts all attempts.
     *
     * <p>Uses {@code instanceId} instead of the human-readable name to determine
     * whether the exception originated from this retry instance.
     */
    public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
        try {
            return execute(callable);
        } catch (RetryException e) {
            if (Objects.equals(e.getInstanceId(), this.instanceId)) {
                return fallback.get();
            }
            throw e;
        }
    }

    // ======================== Internal — Backoff ========================

    /**
     * Parks the current thread until the target wakeup time.
     *
     * <p><strong>Fix 1:</strong> Throws a real {@link InterruptedException} instead
     * of the previous unchecked {@code RetryInterruptedException}. This ensures
     * callers that catch {@code InterruptedException} (including the retry loop
     * itself) see the correct exception type and can handle interrupts properly.
     *
     * <p>Note: {@link LockSupport#parkNanos} does NOT consume the thread's
     * interrupt status (unlike {@link Thread#sleep}). We check
     * {@code isInterrupted()} explicitly at the top of each loop iteration.
     *
     * @param targetWakeup when the backoff delay expires
     * @throws InterruptedException if the thread is interrupted while parked
     */
    private void parkUntil(Instant targetWakeup) throws InterruptedException {
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException(
                        "Thread interrupted during retry backoff for '%s'".formatted(config.name()));
            }

            Duration remaining = Duration.between(clock.instant(), targetWakeup);
            if (remaining.isNegative() || remaining.isZero()) {
                break;
            }
            LockSupport.parkNanos(remaining.toNanos());
        }
    }

    // ======================== Internal — Event emission ========================

    /**
     * Fix 11: Emits a RETRY_SCHEDULED event using the failure from the updated snapshot,
     * not from the stale pre-decision snapshot.
     */
    private void emitExceptionRetryEvent(RetrySnapshot updatedSnapshot, RetryDecision.DoRetry doRetry) {
        Instant now = clock.instant();
        Duration elapsed = updatedSnapshot.totalElapsed(now);
        emitEvent(RetryEvent.retryScheduled(
                config.name(), updatedSnapshot.attemptNumber(), doRetry.delay(),
                elapsed, updatedSnapshot.lastFailure(), now));
    }

    private void emitResultRetryEvent(RetrySnapshot updatedSnapshot, RetryDecision.DoRetry doRetry) {
        Instant now = clock.instant();
        Duration elapsed = updatedSnapshot.totalElapsed(now);
        emitEvent(RetryEvent.resultRetryScheduled(
                config.name(), updatedSnapshot.attemptNumber(), doRetry.delay(), elapsed, now));
    }

    private void emitExhaustedEvent(RetrySnapshot updatedSnapshot) {
        Instant now = clock.instant();
        Duration elapsed = updatedSnapshot.totalElapsed(now);
        emitEvent(RetryEvent.retriesExhausted(
                config.name(), updatedSnapshot.attemptNumber(), elapsed,
                updatedSnapshot.lastFailure(), now));
    }

    // ======================== Listeners ========================

    /**
     * Registers an event listener.
     *
     * <p><strong>Fix 4:</strong> Returns a {@link Runnable} that, when executed,
     * unregisters this listener. Prevents memory leaks when listeners are
     * registered from short-lived contexts.
     *
     * @param listener the listener to be notified on retry events
     * @return a disposable handle that removes the listener when invoked
     */
    public Runnable onEvent(Consumer<RetryEvent> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        eventListeners.add(listener);
        return () -> eventListeners.remove(listener);
    }

    /**
     * Fix 3: Catches {@link Throwable} (not just {@link Exception}) to prevent
     * an {@link Error} from a monitoring listener from aborting the entire retry
     * sequence. Consistent with CircuitBreaker and FallbackProvider.
     */
    private void emitEvent(RetryEvent event) {
        for (Consumer<RetryEvent> listener : eventListeners) {
            try {
                listener.accept(event);
            } catch (Throwable t) {
                LOG.log(Level.WARNING,
                        "Event listener threw exception for retry '%s' (event: %s): %s"
                                .formatted(config.name(), event.type(), t.getMessage()),
                        t);
            }
        }
    }

    // ======================== Introspection ========================

    public RetryConfig getConfig() {
        return config;
    }

    public String getInstanceId() {
        return instanceId;
    }
}
