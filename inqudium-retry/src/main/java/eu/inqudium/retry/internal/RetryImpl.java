package eu.inqudium.retry.internal;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.retry.RetryBehavior;
import eu.inqudium.core.retry.RetryConfig;
import eu.inqudium.core.retry.InqRetryExhaustedException;
import eu.inqudium.retry.Retry;
import eu.inqudium.retry.event.RetryOnRetryEvent;
import eu.inqudium.retry.event.RetryOnSuccessEvent;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Imperative retry implementation using {@link LockSupport#parkNanos} (ADR-008).
 *
 * @since 0.1.0
 */
public final class RetryImpl implements Retry {

    private final String name;
    private final RetryConfig config;
    private final RetryBehavior behavior;
    private final InqEventPublisher eventPublisher;

    public RetryImpl(String name, RetryConfig config) {
        this.name = name;
        this.config = config;
        this.behavior = RetryBehavior.defaultBehavior();
        this.eventPublisher = InqEventPublisher.create(name, InqElementType.RETRY);
    }

    @Override public String getName() { return name; }
    @Override public RetryConfig getConfig() { return config; }
    @Override public InqEventPublisher getEventPublisher() { return eventPublisher; }

    @Override
    public <T> Supplier<T> decorateSupplier(Supplier<T> supplier) {
        return () -> {
            var call = InqCall.of(config.getCallIdGenerator().generate(), supplier);
            return executeCall(call);
        };
    }

    @Override
    public <T> InqCall<T> decorate(InqCall<T> call) {
        return call.withSupplier(() -> executeCall(call));
    }

    @Override
    public <T> Supplier<T> decorateCallable(Callable<T> callable) {
        return decorateSupplier(() -> {
            try { return callable.call(); }
            catch (RuntimeException re) { throw re; }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Override
    public Runnable decorateRunnable(Runnable runnable) {
        Supplier<Void> s = decorateSupplier(() -> { runnable.run(); return null; });
        return s::get;
    }

    private <T> T executeCall(InqCall<T> call) {
        var callId = call.callId();
        Throwable lastException = null;

        for (int attempt = 1; attempt <= config.getMaxAttempts(); attempt++) {
            try {
                T result = call.supplier().get();
                eventPublisher.publish(new RetryOnSuccessEvent(
                        callId, name, attempt, config.getClock().instant()));
                return result;
            } catch (Exception e) {
                lastException = e;
                var maybeDelay = behavior.shouldRetry(attempt, e, config);
                if (maybeDelay.isEmpty()) break;

                var delay = maybeDelay.get();
                eventPublisher.publish(new RetryOnRetryEvent(
                        callId, name, attempt, delay, e, config.getClock().instant()));
                LockSupport.parkNanos(delay.toNanos());
            }
        }
        throw new InqRetryExhaustedException(name, config.getMaxAttempts(), lastException);
    }
}
