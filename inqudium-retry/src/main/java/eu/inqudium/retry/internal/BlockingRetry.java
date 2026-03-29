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

import java.util.concurrent.locks.LockSupport;

/**
 * Imperative retry implementation using {@link LockSupport#parkNanos} (ADR-008).
 *
 * @since 0.1.0
 */
public final class BlockingRetry implements Retry {

    private final String name;
    private final RetryConfig config;
    private final RetryBehavior behavior;
    private final InqEventPublisher eventPublisher;

    public BlockingRetry(String name, RetryConfig config) {
        this.name = name;
        this.config = config;
        this.behavior = RetryBehavior.defaultBehavior();
        this.eventPublisher = InqEventPublisher.create(name, InqElementType.RETRY);
    }

    @Override public String getName() { return name; }
    @Override public RetryConfig getConfig() { return config; }
    @Override public InqEventPublisher getEventPublisher() { return eventPublisher; }

    @Override
    public <T> InqCall<T> decorate(InqCall<T> call) {
        return call.withCallable(() -> executeCall(call));
    }

    private <T> T executeCall(InqCall<T> call) throws Exception {
        var callId = call.callId();
        Throwable lastException = null;

        for (int attempt = 1; attempt <= config.getMaxAttempts(); attempt++) {
            try {
                T result = call.callable().call();
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
        throw new InqRetryExhaustedException(call.callId(), name, config.getMaxAttempts(), lastException);
    }
}
