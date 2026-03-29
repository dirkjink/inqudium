package eu.inqudium.ratelimiter.internal;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.exception.InqRuntimeException;
import eu.inqudium.core.ratelimiter.*;
import eu.inqudium.ratelimiter.RateLimiter;
import eu.inqudium.ratelimiter.event.RateLimiterOnPermitEvent;
import eu.inqudium.ratelimiter.event.RateLimiterOnRejectEvent;

import java.util.concurrent.Callable;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Token-bucket rate limiter using {@link AtomicReference} for lock-free state (ADR-019).
 *
 * @since 0.1.0
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private final String name;
    private final RateLimiterConfig config;
    private final RateLimiterBehavior behavior;
    private final InqEventPublisher eventPublisher;
    private final AtomicReference<TokenBucketState> stateRef;

    public TokenBucketRateLimiter(String name, RateLimiterConfig config) {
        this.name = name;
        this.config = config;
        this.behavior = RateLimiterBehavior.defaultBehavior();
        this.eventPublisher = InqEventPublisher.create(name, InqElementType.RATE_LIMITER);
        this.stateRef = new AtomicReference<>(TokenBucketState.initial(config));
    }

    @Override public String getName() { return name; }
    @Override public RateLimiterConfig getConfig() { return config; }
    @Override public InqEventPublisher getEventPublisher() { return eventPublisher; }

    @Override
    public void acquirePermit() {
        acquirePermitWithCallId(config.getCallIdGenerator().generate());
    }

    @Override
    public <T> Supplier<T> decorateCallable(Callable<T> callable) {
        return () -> {
            var callId = config.getCallIdGenerator().generate();
            acquirePermitWithCallId(callId);
            return InqRuntimeException.wrapCallable(callable, callId, name, InqElementType.RATE_LIMITER).get();
        };
    }

    @Override
    public <T> InqCall<T> decorate(InqCall<T> call) {
        return call.withSupplier(() -> {
            acquirePermitWithCallId(call.callId());
            return call.supplier().get();
        });
    }

    private void acquirePermitWithCallId(String callId) {
        var deadline = config.getTimeoutDuration().isZero()
                ? Instant.MIN
                : config.getClock().instant().plus(config.getTimeoutDuration());

        while (true) {
            var currentState = stateRef.get();
            var result = behavior.tryAcquire(currentState, config);

            if (result.permitted()) {
                if (stateRef.compareAndSet(currentState, result.updatedState())) {
                    eventPublisher.publish(new RateLimiterOnPermitEvent(
                            callId, name, result.updatedState().availableTokens(),
                            config.getClock().instant()));
                    return;
                }
                continue;
            }

            if (config.getTimeoutDuration().isZero()) {
                eventPublisher.publish(new RateLimiterOnRejectEvent(
                        callId, name, result.waitDuration(), config.getClock().instant()));
                throw new InqRequestNotPermittedException(callId, name, result.waitDuration());
            }

            var now = config.getClock().instant();
            if (now.isAfter(deadline)) {
                eventPublisher.publish(new RateLimiterOnRejectEvent(
                        callId, name, result.waitDuration(), now));
                throw new InqRequestNotPermittedException(callId, name, result.waitDuration());
            }

            var remaining = Duration.between(now, deadline);
            var parkDuration = result.waitDuration().compareTo(remaining) < 0
                    ? result.waitDuration() : remaining;
            LockSupport.parkNanos(parkDuration.toNanos());
        }
    }
}
