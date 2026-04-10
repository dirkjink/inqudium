package eu.inqudium.imperative.timelimiter;

import eu.inqudium.core.element.timelimiter.ExecutionSnapshot;
import eu.inqudium.core.element.timelimiter.TimeLimiterCore;
import eu.inqudium.core.element.timelimiter.TimeLimiterEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Default non-blocking execution strategy using {@link CompletableFuture}.
 *
 * <p>All three execution modes share a bulkhead pipeline backbone
 * ({@link #attachTimeoutAndEvents}) that attaches:
 * <ol>
 *   <li>{@link CompletableFuture#orTimeout} — deadline scheduling via the JDK's
 *       internal {@code ScheduledThreadPoolExecutor}</li>
 *   <li>{@link CompletableFuture#handle} — event emission and exception
 *       transformation ({@code TimeoutException} → {@code TimeLimiterException})</li>
 *   <li>A mode-specific cancellation callback invoked on timeout</li>
 * </ol>
 *
 * <h2>Cancellation semantics per mode</h2>
 * <table>
 *   <tr><th>Mode</th><th>On timeout</th><th>Interrupts?</th></tr>
 *   <tr><td>Callable</td><td>{@code vThread.interrupt()}</td><td>Yes</td></tr>
 *   <tr><td>External Future</td><td>{@code future.cancel(true)}</td><td>Impl-dependent</td></tr>
 *   <tr><td>CompletionStage</td><td>{@code cf.cancel(true)}</td><td>No</td></tr>
 * </table>
 */
public class CompletableFutureAsyncExecutor implements TimeLimiterAsyncExecutor {

    private final TimeLimiterContext ctx;

    public CompletableFutureAsyncExecutor(TimeLimiterContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx must not be null");
    }

    // ======================== Callable ========================

    private static Throwable unwrapCompletionException(Throwable ex) {
        return (ex instanceof CompletionException && ex.getCause() != null)
                ? ex.getCause() : ex;
    }

    // ======================== External Future ========================

    @Override
    public <T> CompletableFuture<T> executeAsync(Callable<T> callable, Duration timeout) {
        Instant now = ctx.clock().instant();
        ExecutionSnapshot snapshot = TimeLimiterCore.start(ctx.config(), timeout, now);
        ctx.emitEvent(TimeLimiterEvent.started(ctx.config().name(), timeout, now));

        // Run callable in a virtual thread, bridged to a CompletableFuture
        CompletableFuture<T> cf = new CompletableFuture<>();
        Thread vThread = Thread.ofVirtual().name(ctx.nextThreadName()).start(() -> {
            try {
                cf.complete(callable.call());
            } catch (Throwable t) {
                cf.completeExceptionally(t);
            }
        });

        // On timeout: interrupt the virtual thread for true cancellation
        return attachTimeoutAndEvents(cf, snapshot, timeout, () -> vThread.interrupt());
    }

    // ======================== CompletionStage ========================

    @Override
    public <T> CompletableFuture<T> executeFutureAsync(
            Supplier<Future<T>> futureSupplier, Duration timeout) {

        Instant now = ctx.clock().instant();
        ExecutionSnapshot snapshot = TimeLimiterCore.start(ctx.config(), timeout, now);
        ctx.emitEvent(TimeLimiterEvent.started(ctx.config().name(), timeout, now));

        Future<T> future = futureSupplier.get();

        // Bridge Future -> CompletableFuture via virtual thread.
        // Necessary because Future has no non-blocking completion API.
        // The virtual thread blocks on future.get() (cheap on virtual threads)
        // while the CF gets the timeout attached non-blockingly via orTimeout.
        CompletableFuture<T> cf = new CompletableFuture<>();
        Thread.ofVirtual().name(ctx.nextBridgeThreadName()).start(() -> {
            try {
                cf.complete(future.get());
            } catch (ExecutionException e) {
                cf.completeExceptionally(e.getCause() != null ? e.getCause() : e);
            } catch (InterruptedException e) {
                // Bridge thread was interrupted (typically by orTimeout cancellation).
                // The CF is already completed with TimeoutException by orTimeout —
                // do not overwrite it. Just restore the interrupt flag.
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                cf.completeExceptionally(t);
            }
        });

        // On timeout: cancel the original Future (may interrupt the real operation)
        return attachTimeoutAndEvents(cf, snapshot, timeout, () -> future.cancel(true));
    }

    // ======================== Shared pipeline ========================

    /**
     * Attaches timeout protection directly to the existing pipeline.
     *
     * <p>No additional thread is spawned. The timeout is enforced via
     * {@link CompletableFuture#orTimeout}, and event emission is added as a
     * dependent stage via {@link CompletableFuture#handle}.
     *
     * <p><strong>Cancellation caveat:</strong> {@code CompletableFuture.cancel(true)}
     * does not interrupt the underlying computation. For cooperative cancellation,
     * the supplier should inspect the future's state or use a shared signal.
     */
    @Override
    public <T> CompletableFuture<T> executeCompletionStageAsync(
            Supplier<CompletionStage<T>> stageSupplier, Duration timeout) {

        Instant now = ctx.clock().instant();
        ExecutionSnapshot snapshot = TimeLimiterCore.start(ctx.config(), timeout, now);
        ctx.emitEvent(TimeLimiterEvent.started(ctx.config().name(), timeout, now));

        CompletableFuture<T> cf = stageSupplier.get().toCompletableFuture();

        // Attach directly to the existing pipeline — no new thread spawned
        return attachTimeoutAndEvents(cf, snapshot, timeout, () -> cf.cancel(true));
    }

    /**
     * Attaches timeout enforcement, event emission, and exception transformation
     * to a {@link CompletableFuture}.
     *
     * @param cf              the future to protect
     * @param snapshot        the execution snapshot (RUNNING state)
     * @param timeout         the effective timeout duration
     * @param onTimeoutCancel cancellation action specific to the execution mode
     * @return a new dependent future with timeout and event handling attached
     */
    private <T> CompletableFuture<T> attachTimeoutAndEvents(
            CompletableFuture<T> cf,
            ExecutionSnapshot snapshot,
            Duration timeout,
            Runnable onTimeoutCancel) {

        String name = ctx.config().name();

        // Schedule the deadline on the CF (modifies in place, returns same reference)
        cf.orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);

        // Intercept completion to emit events and transform exceptions
        return cf.handle((result, ex) -> {
            Instant eventTime = ctx.clock().instant();

            // Happy path: completed within the deadline
            if (ex == null) {
                ExecutionSnapshot completed = TimeLimiterCore.recordSuccess(snapshot, eventTime);
                ctx.emitEvent(TimeLimiterEvent.completed(
                        name, completed.elapsed(eventTime), timeout, eventTime));
                return result;
            }

            Throwable cause = unwrapCompletionException(ex);

            // Timeout path: orTimeout completed the CF with TimeoutException
            if (cause instanceof TimeoutException) {
                ExecutionSnapshot timedOut = TimeLimiterCore.recordTimeout(snapshot, eventTime);
                ctx.emitEvent(TimeLimiterEvent.timedOut(
                        name, timedOut.elapsed(eventTime), timeout, eventTime));

                if (ctx.config().cancelOnTimeout()) {
                    onTimeoutCancel.run();
                    Instant cancelledAt = ctx.clock().instant();
                    ExecutionSnapshot cancelled = TimeLimiterCore.recordCancellation(timedOut, cancelledAt);
                    ctx.emitEvent(TimeLimiterEvent.cancelled(
                            name, cancelled.elapsed(cancelledAt), timeout, cancelledAt));
                }

                throw new CompletionException(ctx.createTimeoutException(timeout));
            }

            // Failure path: operation threw within the deadline
            ExecutionSnapshot failed = TimeLimiterCore.recordFailure(snapshot, cause, eventTime);
            ctx.emitEvent(TimeLimiterEvent.failed(
                    name, failed.elapsed(eventTime), timeout, eventTime));
            throw new CompletionException(cause);
        });
    }
}
