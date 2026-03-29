package eu.inqudium.timelimiter.internal;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.exception.InqRuntimeException;
import eu.inqudium.core.timelimiter.InqTimeLimitExceededException;
import eu.inqudium.core.timelimiter.TimeLimiterConfig;
import eu.inqudium.timelimiter.TimeLimiter;
import eu.inqudium.timelimiter.event.TimeLimiterOnErrorEvent;
import eu.inqudium.timelimiter.event.TimeLimiterOnSuccessEvent;
import eu.inqudium.timelimiter.event.TimeLimiterOnTimeoutEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Imperative time limiter using {@link CompletableFuture#get(long, TimeUnit)} (ADR-010).
 *
 * <p>The core method {@link #executeFuture} declares {@code throws Exception} so that
 * checked exceptions flow naturally through the pipeline. Wrapping in
 * {@link InqRuntimeException} happens only at the {@code Supplier} boundary in
 * {@link #decorateCallable}.
 *
 * @since 0.1.0
 */
public final class FutureTimeLimiter implements TimeLimiter {

    private static final Executor VIRTUAL_THREAD_EXECUTOR =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("inq-tl-", 0).factory());

    private final String name;
    private final TimeLimiterConfig config;
    private final InqEventPublisher eventPublisher;

    public FutureTimeLimiter(String name, TimeLimiterConfig config) {
        this.name = name;
        this.config = config;
        this.eventPublisher = InqEventPublisher.create(name, InqElementType.TIME_LIMITER);
    }

    @Override public String getName() { return name; }
    @Override public TimeLimiterConfig getConfig() { return config; }
    @Override public InqEventPublisher getEventPublisher() { return eventPublisher; }

    @Override
    public <T> Supplier<T> decorateFutureSupplier(Supplier<CompletionStage<T>> futureSupplier) {
        return () -> {
            var callId = config.getCallIdGenerator().generate();
            try {
                return executeFuture(callId, futureSupplier);
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new InqRuntimeException(callId, name, InqElementType.TIME_LIMITER, e);
            }
        };
    }

    @Override
    public <T> Supplier<T> decorateCallable(Callable<T> callable) {
        return () -> {
            var callId = config.getCallIdGenerator().generate();
            try {
                return executeFuture(callId, () ->
                        CompletableFuture.supplyAsync(
                                toSupplier(callable), VIRTUAL_THREAD_EXECUTOR));
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new InqRuntimeException(callId, name, InqElementType.TIME_LIMITER, e);
            }
        };
    }

    @Override
    public <T> InqCall<T> decorate(InqCall<T> call) {
        // Callable→Callable: checked exceptions flow naturally via withCallable
        return call.withCallable(() ->
                executeFuture(call.callId(), () ->
                        CompletableFuture.supplyAsync(
                                toSupplier(call.callable()), VIRTUAL_THREAD_EXECUTOR)));
    }

    /**
     * Core execution method — throws Exception so checked exceptions flow naturally
     * through the pipeline without intermediate wrapping.
     *
     * <p>Unwraps {@link ExecutionException} and {@link CompletionException} to recover
     * the original exception from the {@code supplyAsync} boundary.
     */
    private <T> T executeFuture(String callId, Supplier<CompletionStage<T>> futureSupplier) throws Exception {
        var start = config.getClock().instant();
        var timeout = config.getTimeoutDuration();

        CompletableFuture<T> future;
        try {
            future = futureSupplier.get().toCompletableFuture();
        } catch (Exception e) {
            var duration = Duration.between(start, config.getClock().instant());
            eventPublisher.publish(new TimeLimiterOnErrorEvent(callId, name, duration, e, config.getClock().instant()));
            throw e;
        }

        try {
            T result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            var duration = Duration.between(start, config.getClock().instant());
            eventPublisher.publish(new TimeLimiterOnSuccessEvent(callId, name, duration, config.getClock().instant()));
            return result;
        } catch (TimeoutException te) {
            var actualDuration = Duration.between(start, config.getClock().instant());
            eventPublisher.publish(new TimeLimiterOnTimeoutEvent(callId, name, timeout, config.getClock().instant()));
            installOrphanedHandlers(future, callId, start);
            throw new InqTimeLimitExceededException(callId, name, timeout, actualDuration);
        } catch (ExecutionException ee) {
            var cause = unwrapCause(ee);
            var duration = Duration.between(start, config.getClock().instant());
            eventPublisher.publish(new TimeLimiterOnErrorEvent(callId, name, duration, cause, config.getClock().instant()));
            throw cause;  // rethrow original — checked exceptions flow naturally
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            var actualDuration = Duration.between(start, config.getClock().instant());
            throw new InqTimeLimitExceededException(callId, name, timeout, actualDuration);
        }
    }

    /**
     * Unwraps the original exception from ExecutionException → CompletionException chains
     * created by the Callable→Supplier→supplyAsync boundary.
     */
    private static Exception unwrapCause(ExecutionException ee) {
        var cause = ee.getCause();
        // supplyAsync wraps in CompletionException → unwrap
        if (cause instanceof CompletionException ce && ce.getCause() != null) {
            cause = ce.getCause();
        }
        return cause instanceof Exception ex ? ex : new RuntimeException(cause);
    }

    /**
     * Converts a Callable to a Supplier for CompletableFuture.supplyAsync.
     * Checked exceptions are wrapped in CompletionException — unwrapped by executeFuture.
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

    private <T> void installOrphanedHandlers(CompletableFuture<T> future, String callId, Instant start) {
        var onResult = config.getOnOrphanedResult();
        var onError = config.getOnOrphanedError();
        if (onResult == null && onError == null) return;

        future.whenComplete((result, throwable) -> {
            var actualDuration = Duration.between(start, config.getClock().instant());
            var ctx = new TimeLimiterConfig.OrphanedCallContext(
                    name, config.getTimeoutDuration(), actualDuration, callId);
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
                        .warn("[{}] Orphaned call handler threw: {}", callId, e.getMessage());
            }
        });
    }
}
