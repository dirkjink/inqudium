package eu.inqudium.circuitbreaker.internal;

import eu.inqudium.circuitbreaker.CircuitBreaker;
import eu.inqudium.core.circuitbreaker.AbstractCircuitBreaker;
import eu.inqudium.core.circuitbreaker.CircuitBreakerConfig;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Imperative circuit breaker using {@link ReentrantLock} for mutual exclusion (ADR-008).
 *
 * <p>All state machine logic, event publishing, and exception handling live in
 * {@link AbstractCircuitBreaker}. This class only provides the lock mechanism.
 *
 * <p>Virtual-thread safe — {@link ReentrantLock} does not pin carrier threads,
 * unlike {@code synchronized} blocks.
 *
 * @since 0.1.0
 */
public final class CircuitBreakerStateMachine extends AbstractCircuitBreaker implements CircuitBreaker {

  private final ReentrantLock lock = new ReentrantLock();

  public CircuitBreakerStateMachine(String name, CircuitBreakerConfig config) {
    super(name, config);
  }

  @Override
  protected void lock() {
    lock.lock();
  }

  @Override
  protected void unlock() {
    lock.unlock();
  }
}
