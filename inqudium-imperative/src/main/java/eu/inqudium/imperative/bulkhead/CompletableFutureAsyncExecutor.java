package eu.inqudium.imperative.bulkhead;

import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.element.bulkhead.InqBulkheadInterruptedException;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.element.bulkhead.strategy.RejectionContext;
import eu.inqudium.imperative.core.InqAsyncExecutor;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Bulkhead-aware async executor that extends {@link CompletableFuture} pipelines
 * with acquire/release semantics.
 *
 * <h2>Execution model</h2>
 * <p>Permit acquisition is <b>synchronous</b> on the calling thread — if the bulkhead
 * is full, the caller blocks (up to {@code maxWaitDuration}) or receives an
 * {@link InqBulkheadFullException}. This is intentional: the caller must know
 * immediately whether the request was admitted.
 *
 * <p>Permit release is <b>asynchronous</b> — it fires as a dependent action on the
 * {@link CompletableFuture} pipeline when the business operation completes (success,
 * failure, or cancellation). This ensures the permit is held for exactly the duration
 * of the async work, not the duration of the calling thread's wait.
 *
 * <h2>Pipeline identity</h2>
 * <p>For {@link #executeCompletionStageAsync} and {@link #executeFutureAsync}, the
 * returned {@link CompletableFuture} is the <b>same object</b> that the supplier
 * produced. The release handler is attached as a dependent action via
 * {@link CompletableFuture#whenComplete} — the returned reference from
 * {@code whenComplete} is discarded, not returned to the caller. This preserves
 * reference identity: {@code returnedFuture == supplierFuture}.
 *
 * <h2>RTT measurement</h2>
 * <p>The RTT clock starts immediately after successful permit acquisition and stops
 * in the {@code whenComplete} handler. This captures the full async execution time,
 * including any scheduling delays — which is the relevant signal for adaptive
 * concurrency algorithms (AIMD, Vegas, CoDel).
 *
 * @since 0.3.0
 */
public final class CompletableFutureAsyncExecutor implements InqAsyncExecutor {

  private final BulkheadContext ctx;

  CompletableFutureAsyncExecutor(BulkheadContext ctx) {
    this.ctx = Objects.requireNonNull(ctx, "context must not be null");
  }

  // ════════════════════════════════════════════════════════════════════
  // InqAsyncExecutor — public API
  // ════════════════════════════════════════════════════════════════════

  /**
   * {@inheritDoc}
   *
   * <p>Acquires a permit synchronously, then submits the callable to the
   * {@link java.util.concurrent.ForkJoinPool#commonPool() common pool}.
   * The permit is released when the future completes.
   */
  @Override
  public <T> CompletableFuture<T> executeAsync(Callable<T> callable) {
    Objects.requireNonNull(callable, "callable must not be null");

    long startNanos = acquirePermit();

    CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
      try {
        return callable.call();
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new CompletionException(e);
      }
    });

    // Attach release as dependent action; discard the returned CF
    future.whenComplete((result, error) ->
        releasePermit(startNanos, error == null));

    return future;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Acquires a permit synchronously, obtains the {@link Future} from the supplier,
   * and attaches permit release to its completion. If the future is a
   * {@link CompletableFuture}, it is returned directly (same object identity).
   * Otherwise, a bridging {@link CompletableFuture} is created that polls the
   * external future.
   */
  @Override
  public <T> CompletableFuture<T> executeFutureAsync(Supplier<Future<T>> futureSupplier) {
    Objects.requireNonNull(futureSupplier, "futureSupplier must not be null");

    long startNanos = acquirePermit();

    Future<T> future = futureSupplier.get();

    if (future instanceof CompletableFuture<T> cf) {
      // Fast path: same object identity preserved
      cf.whenComplete((result, error) ->
          releasePermit(startNanos, error == null));
      return cf;
    }

    // Slow path: bridge a non-CompletableFuture via blocking get on common pool.
    // The permit is held until the bridging thread completes the get().
    return CompletableFuture.supplyAsync(() -> {
      try {
        T result = future.get();
        releasePermit(startNanos, true);
        return result;
      } catch (Exception e) {
        releasePermit(startNanos, false);
        throw (e instanceof RuntimeException re) ? re : new CompletionException(e);
      }
    });
  }

  /**
   * {@inheritDoc}
   *
   * <p>Acquires a permit synchronously, obtains the {@link CompletionStage} from
   * the supplier, and attaches permit release as a dependent action. The returned
   * {@link CompletableFuture} is the <b>same object</b> produced by
   * {@code stageSupplier.get().toCompletableFuture()} — no wrapping, no copying.
   */
  @Override
  public <T> CompletableFuture<T> executeCompletionStageAsync(
      Supplier<CompletionStage<T>> stageSupplier) {
    Objects.requireNonNull(stageSupplier, "stageSupplier must not be null");

    long startNanos = acquirePermit();

    CompletableFuture<T> future = stageSupplier.get().toCompletableFuture();

    // Attach release; discard the new CF from whenComplete — return the original
    future.whenComplete((result, error) ->
        releasePermit(startNanos, error == null));

    return future;
  }

  // ════════════════════════════════════════════════════════════════════
  // Acquire / Release — shared by all execution modes
  // ════════════════════════════════════════════════════════════════════

  /**
   * Acquires a permit synchronously. On success, publishes the diagnostic acquire
   * event (if enabled) and returns the nanoTime for RTT measurement.
   *
   * @return nanoTime immediately after successful acquisition (RTT start)
   * @throws InqBulkheadFullException        if the bulkhead is full
   * @throws InqBulkheadInterruptedException if the thread is interrupted while waiting
   */
  private long acquirePermit() {
    RejectionContext rejection;
    try {
      rejection = ctx.strategy().tryAcquire(ctx.maxWaitDuration());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      publishRejectionEvent(null);
      throw new InqBulkheadInterruptedException("async", ctx.bulkheadName(), ctx.isEnableExceptionOptimization());
    }

    if (rejection != null) {
      publishRejectionEvent(rejection);
      throw new InqBulkheadFullException("async", ctx.bulkheadName(), rejection, ctx.isEnableExceptionOptimization());
    }

    publishAcquireEvent();

    return ctx.nanoTimeSource().now();
  }

  /**
   * Releases the permit, feeds the adaptive algorithm, and publishes the
   * diagnostic release event (if enabled). Safe to call from any thread —
   * typically invoked from a {@code whenComplete} handler on the CF pipeline.
   */
  private void releasePermit(long startNanos, boolean success) {
    long rttNanos = ctx.nanoTimeSource().now() - startNanos;

    try {
      ctx.strategy().onCallComplete(rttNanos, success);
    } catch (RuntimeException algorithmError) {
      ctx.logger().error().log("Adaptive algorithm hook failed for bulkhead '{}' (async). "
          + "Permit will still be released.", ctx.bulkheadName(), algorithmError);
    } finally {
      try {
        ctx.strategy().release();
      } catch (RuntimeException releaseError) {
        ctx.logger().error().log("Strategy release failed for bulkhead '{}' (async).",
            ctx.bulkheadName(), releaseError);
      }
    }

    publishReleaseEvent();
  }

  // ════════════════════════════════════════════════════════════════════
  // Diagnostic events — same conditional structure as ImperativeBulkhead
  // ════════════════════════════════════════════════════════════════════

  private void publishAcquireEvent() {
    if (ctx.eventConfig().isLifecycleEnabled()) {
      try {
        ctx.eventPublisher().publish(new BulkheadOnAcquireEvent(
            "async", ctx.bulkheadName(), ctx.strategy().concurrentCalls(), ctx.clock().instant()));
      } catch (RuntimeException e) {
        // Rollback: the acquire already happened, but if the event publisher fails,
        // we must undo the acquire to maintain consistency
        ctx.strategy().rollback();
        throw e;
      }
    }
  }

  private void publishReleaseEvent() {
    if (ctx.eventConfig().isLifecycleEnabled()) {
      try {
        ctx.eventPublisher().publish(new BulkheadOnReleaseEvent(
            "async", ctx.bulkheadName(), ctx.strategy().concurrentCalls(), ctx.clock().instant()));
      } catch (RuntimeException e) {
        ctx.logger().error().log("Failed to publish async release event for bulkhead '{}'.",
            ctx.bulkheadName(), e);
      }
    }
  }

  private void publishRejectionEvent(RejectionContext rejection) {
    if (ctx.eventConfig().isRejectionEnabled()) {
      try {
        ctx.eventPublisher().publish(new BulkheadOnRejectEvent(
            "async", ctx.bulkheadName(), rejection, ctx.clock().instant()));
      } catch (RuntimeException e) {
        ctx.logger().error().log("Failed to publish async reject event for bulkhead '{}'.",
            ctx.bulkheadName(), e);
      }
    }
  }
}
