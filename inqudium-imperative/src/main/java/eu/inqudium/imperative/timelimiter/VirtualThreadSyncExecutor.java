package eu.inqudium.imperative.timelimiter;

import eu.inqudium.core.element.timelimiter.ExecutionSnapshot;
import eu.inqudium.core.element.timelimiter.TimeLimiterCore;
import eu.inqudium.core.element.timelimiter.TimeLimiterEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Default blocking execution strategy using virtual threads.
 *
 * <p>For each callable execution, a virtual thread is spawned to run the
 * operation. The caller thread blocks on {@code Future.get(timeout)} and
 * acts as a watchdog. On timeout, the virtual thread is interrupted via
 * {@code Future.cancel(true)}.
 *
 * <p>For external futures and completion stages, no virtual thread is spawned —
 * the caller blocks directly on the supplied future's {@code get(timeout)}.
 *
 * <p>This implementation is optimised for I/O-bound operations where the
 * overhead of spawning a virtual thread is negligible.
 */
public class VirtualThreadSyncExecutor implements TimeLimiterSyncExecutor {

    private final TimeLimiterContext ctx;

    public VirtualThreadSyncExecutor(TimeLimiterContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx must not be null");
    }

    @Override
    public <T> T execute(Callable<T> callable, Duration timeout) throws Exception {
        Instant now = ctx.clock().instant();
        ExecutionSnapshot snapshot = TimeLimiterCore.start(ctx.config(), timeout, now);
        ctx.emitEvent(TimeLimiterEvent.started(ctx.config().name(), timeout, now));

        FutureTask<T> task = new FutureTask<>(callable);
        Thread.ofVirtual().name(ctx.nextThreadName()).start(task);

        try {
            return awaitFuture(task, snapshot, timeout);
        } catch (Throwable t) {
            ctx.cancelSafely(task);
            throw t;
        }
    }

    @Override
    public <T> T executeFuture(Supplier<Future<T>> futureSupplier, Duration timeout) throws Exception {
        Instant now = ctx.clock().instant();
        ExecutionSnapshot snapshot = TimeLimiterCore.start(ctx.config(), timeout, now);
        ctx.emitEvent(TimeLimiterEvent.started(ctx.config().name(), timeout, now));

        Future<T> future = futureSupplier.get();
        try {
            return awaitFuture(future, snapshot, timeout);
        } catch (Throwable t) {
            ctx.cancelSafely(future);
            throw t;
        }
    }

    @Override
    public <T> T executeCompletionStage(
            Supplier<CompletionStage<T>> stageSupplier, Duration timeout) throws Exception {
        return executeFuture(() -> stageSupplier.get().toCompletableFuture(), timeout);
    }

    // ======================== Internal — Future awaiting ========================

    private <T> T awaitFuture(
            Future<T> future,
            ExecutionSnapshot snapshot,
            Duration timeout) throws Exception {

        String name = ctx.config().name();

        try {
            T result = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);

            Instant completedAt = ctx.clock().instant();
            ExecutionSnapshot completed = TimeLimiterCore.recordSuccess(snapshot, completedAt);
            ctx.emitEvent(TimeLimiterEvent.completed(
                    name, completed.elapsed(completedAt), timeout, completedAt));
            return result;

        } catch (TimeoutException e) {
            Instant timedOutAt = ctx.clock().instant();
            ExecutionSnapshot timedOut = TimeLimiterCore.recordTimeout(snapshot, timedOutAt);
            ctx.emitEvent(TimeLimiterEvent.timedOut(
                    name, timedOut.elapsed(timedOutAt), timeout, timedOutAt));

            if (ctx.config().cancelOnTimeout()) {
                boolean cancelled = future.cancel(true);
                if (cancelled) {
                    Instant cancelledAt = ctx.clock().instant();
                    ExecutionSnapshot cs = TimeLimiterCore.recordCancellation(timedOut, cancelledAt);
                    ctx.emitEvent(TimeLimiterEvent.cancelled(
                            name, cs.elapsed(cancelledAt), timeout, cancelledAt));
                }
            }

            throw ctx.createTimeoutException(timeout);

        } catch (ExecutionException e) {
            Instant failedAt = ctx.clock().instant();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            ExecutionSnapshot failed = TimeLimiterCore.recordFailure(snapshot, cause, failedAt);
            ctx.emitEvent(TimeLimiterEvent.failed(
                    name, failed.elapsed(failedAt), timeout, failedAt));

            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Instant failedAt = ctx.clock().instant();
            ExecutionSnapshot failed = TimeLimiterCore.recordFailure(snapshot, e, failedAt);
            ctx.emitEvent(TimeLimiterEvent.failed(
                    name, failed.elapsed(failedAt), timeout, failedAt));
            ctx.cancelSafely(future);
            throw e;
        }
    }
}
