package eu.inqudium.bulkhead.internal;

import eu.inqudium.bulkhead.Bulkhead;
import eu.inqudium.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.event.InqEventPublisher;

import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Semaphore-based bulkhead using fair {@link Semaphore} (ADR-008, ADR-020).
 *
 * @since 0.1.0
 */
public final class SemaphoreBulkhead implements Bulkhead {

    private final String name;
    private final BulkheadConfig config;
    private final InqEventPublisher eventPublisher;
    private final Semaphore semaphore;

    public SemaphoreBulkhead(String name, BulkheadConfig config) {
        this.name = name;
        this.config = config;
        this.eventPublisher = InqEventPublisher.create(name, InqElementType.BULKHEAD);
        this.semaphore = new Semaphore(config.getMaxConcurrentCalls(), true);
    }

    @Override public String getName() { return name; }
    @Override public BulkheadConfig getConfig() { return config; }
    @Override public InqEventPublisher getEventPublisher() { return eventPublisher; }
    @Override public int getConcurrentCalls() { return config.getMaxConcurrentCalls() - semaphore.availablePermits(); }
    @Override public int getAvailablePermits() { return semaphore.availablePermits(); }

    @Override
    public <T> InqCall<T> decorate(InqCall<T> call) {
        return call.withCallable(() -> {
            acquirePermit(call.callId());
            try {
                return call.callable().call();
            } finally {
                releasePermit(call.callId());
            }
        });
    }

    private void acquirePermit(String callId) {
        boolean acquired;
        try {
            if (config.getMaxWaitDuration().isZero()) {
                acquired = semaphore.tryAcquire();
            } else {
                acquired = semaphore.tryAcquire(
                        config.getMaxWaitDuration().toNanos(), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }

        if (!acquired) {
            int concurrent = getConcurrentCalls();
            eventPublisher.publish(new BulkheadOnRejectEvent(
                    callId, name, concurrent, config.getClock().instant()));
            throw new InqBulkheadFullException(callId, name, concurrent, config.getMaxConcurrentCalls());
        }

        eventPublisher.publish(new BulkheadOnAcquireEvent(
                callId, name, getConcurrentCalls(), config.getClock().instant()));
    }

    private void releasePermit(String callId) {
        semaphore.release();
        eventPublisher.publish(new BulkheadOnReleaseEvent(
                callId, name, getConcurrentCalls(), config.getClock().instant()));
    }
}
