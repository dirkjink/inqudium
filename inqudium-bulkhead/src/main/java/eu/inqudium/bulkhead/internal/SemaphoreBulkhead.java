package eu.inqudium.bulkhead.internal;

import eu.inqudium.bulkhead.Bulkhead;
import eu.inqudium.core.bulkhead.AbstractBulkhead;
import eu.inqudium.core.bulkhead.BulkheadConfig;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Semaphore-based bulkhead — fair {@link Semaphore} for permit management (ADR-008, ADR-020).
 *
 * <p>All decoration logic, event publishing, and exception handling live in
 * {@link AbstractBulkhead}. This class only provides the blocking permit mechanism.
 *
 * @since 0.1.0
 */
public final class SemaphoreBulkhead extends AbstractBulkhead implements Bulkhead {

  private final Semaphore semaphore;

  public SemaphoreBulkhead(String name, BulkheadConfig config) {
    super(name, config);
    this.semaphore = new Semaphore(config.getMaxConcurrentCalls(), true);
  }

  @Override
  protected boolean tryAcquirePermit(Duration timeout) throws InterruptedException {
    return timeout.isZero()
        ? semaphore.tryAcquire()
        : semaphore.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS);
  }

  @Override
  protected void releasePermit() {
    semaphore.release();
  }

  @Override
  public int getConcurrentCalls() {
    return getConfig().getMaxConcurrentCalls() - semaphore.availablePermits();
  }

  @Override
  public int getAvailablePermits() {
    return semaphore.availablePermits();
  }
}
