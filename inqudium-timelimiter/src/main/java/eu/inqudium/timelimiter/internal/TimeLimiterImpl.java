package eu.inqudium.timelimiter.internal;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.exception.InqRuntimeException;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
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
 * @since 0.1.0
 */
public final class TimeLimiterImpl implements TimeLimiter {

    private static final Executor VIRTUAL_THREAD_EXECUTOR =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("inq-tl-", 0).factory());

    private final String name;
    private final TimeLimiterConfig config;
    private final InqEventPublisher eventPublisher;

    public TimeLimiterImpl(String name, TimeLimiterConfig config) {
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
            return executeFuture(callId, futureSupplier);
        };
    }

    @Override
    public <T> Supplier<T> decorateSupplier(Supplier<T> supplier) {
        return decorateFutureSupplier(() ->
                CompletableFuture.supplyAsync(supplier, VIRTUAL_THREAD_EXECUTOR));
    }

    @Override
    public <T> InqCall<T> decorate(InqCall<T> call) {
        return call.withSupplier(() ->
                executeFuture(call.callId(), () ->
                        CompletableFuture.supplyAsync(call.supplier(), VIRTUAL_THREAD_EXECUTOR)));
    }

    private <T> T executeFuture(String callId, Supplier<CompletionStage<T>> futureSupplier) {
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
            throw new InqTimeLimitExceededException(name, timeout, actualDuration);
        } catch (ExecutionException ee) {
            var duration = Duration.between(start, config.getClock().instant());
            var cause = ee.getCause() != null ? ee.getCause() : ee;
            eventPublisher.publish(new TimeLimiterOnErrorEvent(callId, name, duration, cause, config.getClock().instant()));
            if (cause instanceof RuntimeException re) throw re;
            throw new InqRuntimeException(name, eu.inqudium.core.InqElementType.TIME_LIMITER, cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            var actualDuration = Duration.between(start, config.getClock().instant());
            throw new InqTimeLimitExceededException(name, timeout, actualDuration);
        }
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
                org.slf4j.LoggerFactory.getLogger(TimeLimiterImpl.class)
                        .warn("Orphaned call handler threw: {}", e.getMessage());
            }
        });
    }
}
