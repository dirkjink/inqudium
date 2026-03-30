package eu.inqudium.timelimiter.internal;

import eu.inqudium.core.InqCallIdGenerator;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.exception.InqFailure;
import eu.inqudium.core.exception.InqRuntimeException;
import eu.inqudium.core.timelimiter.AbstractTimeLimiter;
import eu.inqudium.core.timelimiter.TimeLimiterConfig;
import eu.inqudium.timelimiter.TimeLimiter;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Imperative time limiter using {@link CompletableFuture#get(long, TimeUnit)} (ADR-010).
 *
 * <p>All event publishing, exception translation, and timing logic live in
 * {@link AbstractTimeLimiter}. This class only provides:
 * <ul>
 *   <li>The timeout mechanism: {@code CompletableFuture.supplyAsync} + {@code get(timeout)}</li>
 *   <li>Orphaned handler installation via {@code future.whenComplete}</li>
 *   <li>Future-supplier decoration for pre-existing {@link CompletionStage}s</li>
 * </ul>
 *
 * <p>Virtual-thread safe — uses a virtual-thread-per-task executor for
 * {@code supplyAsync}, no carrier-thread pinning.
 *
 * @since 0.1.0
 */
public final class FutureTimeLimiter extends AbstractTimeLimiter implements TimeLimiter {

  private static final Executor VIRTUAL_THREAD_EXECUTOR =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("inq-tl-", 0).factory());

  public FutureTimeLimiter(String name, TimeLimiterConfig config) {
    super(name, config);
  }

  /**
   * Converts a Callable to a Supplier for {@code CompletableFuture.supplyAsync}.
   * Checked exceptions are wrapped in {@link CompletionException} — unwrapped
   * by {@link #futureGet}.
   */
  private static <T> Supplier<T> toSupplier(Callable<T> callable) {
    return () -> {
      try {
        return callable.call();
      } catch (RuntimeException re) {
        throw re;
      } catch (Exception e) {
        throw new CompletionException(e);
      }
    };
  }

  @Override
  protected <T> T executeWithTimeout(String callId, Callable<T> callable,
                                     Duration timeout) throws Exception {
    CompletableFuture<T> future = CompletableFuture.supplyAsync(
        toSupplier(callable), VIRTUAL_THREAD_EXECUTOR);
    return futureGet(callId, future, timeout);
  }

  // ── CompletableFuture mechanics ──

  @Override
  public <T> Supplier<T> decorateFutureSupplier(Supplier<CompletionStage<T>> futureSupplier) {
    return () -> {
      var callId = InqCallIdGenerator.NONE;
      try {
        return timedExecution(callId, () -> {
          CompletableFuture<T> future = futureSupplier.get().toCompletableFuture();
          return futureGet(callId, future, getConfig().getTimeoutDuration());
        });
      } catch (InqException ie) {
        throw ie;
      } catch (RuntimeException re) {
        throw re;
      } catch (Exception e) {
        throw new InqRuntimeException(
            callId, getName(), InqElementType.TIME_LIMITER, e);
      }
    };
  }

  /**
   * Waits for the future with timeout, unwraps execution exceptions,
   * and installs orphaned handlers on timeout.
   */
  private <T> T futureGet(String callId, CompletableFuture<T> future,
                          Duration timeout) throws Exception {
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException te) {
      installOrphanedHandlers(future, callId, getConfig().getClock().instant());
      throw te;
    } catch (ExecutionException ee) {
      var cause = InqFailure.unwrap(ee);
      if (cause instanceof RuntimeException re) throw re;
      if (cause instanceof Exception ex) throw ex;
      throw new RuntimeException(cause);
    }
    // InterruptedException propagates naturally
  }

  private <T> void installOrphanedHandlers(CompletableFuture<T> future, String callId, Instant start) {
    var onResult = getConfig().getOnOrphanedResult();
    var onError = getConfig().getOnOrphanedError();
    if (onResult == null && onError == null) return;

    future.whenComplete((result, throwable) -> {
      var actualDuration = Duration.between(start, getConfig().getClock().instant());
      var ctx = new TimeLimiterConfig.OrphanedCallContext(
          getName(), getConfig().getTimeoutDuration(), actualDuration, callId);
      try {
        if (throwable != null) {
          if (onError != null) {
            var cause = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause() : throwable;
            onError.accept(ctx, cause);
          }
        } else {
          if (onResult != null) onResult.accept(ctx, result);
        }
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(FutureTimeLimiter.class)
            .warn("[{}] Orphaned call handler threw", callId, e);
      }
    });
  }
}
