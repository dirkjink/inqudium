package eu.inqudium.bulkhead.imperative;

import eu.inqudium.bulkhead.Bulkhead;
import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.event.InqEventPublisher;

import java.time.Duration;

/**
 * The imperative facade combining the state machine and the execution strategy.
 *
 * <p>This is the primary entry point for users who want to protect synchronous,
 * thread-blocking calls with a bulkhead. It cleanly hides the complex internal
 * separation of state and execution strategy from the public API.
 *
 * @since 0.2.0
 */
public final class ImperativeBulkhead implements Bulkhead {

  private final String name;
  private final Duration maxWaitDuration;
  private final ImperativeBulkheadStateMachine stateMachine;
  private final BulkheadConfig config;

  public ImperativeBulkhead(String name, BulkheadConfig config) {
    this.name = name;
    this.maxWaitDuration = config.getMaxWaitDuration();
    this.config = config;

    // We instantiate the specific imperative state machine that handles thread-blocking permits
    this.stateMachine = new ImperativeBulkheadStateMachine(name, config);
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public InqElementType getElementType() {
    return InqElementType.BULKHEAD;
  }

  @Override
  public InqEventPublisher getEventPublisher() {
    // The state machine owns the telemetry and the publisher
    return stateMachine.getEventPublisher();
  }

  @Override
  public BulkheadConfig getConfig() {
    return config;
  }

  @Override
  public <T> InqCall<T> decorate(InqCall<T> call) {
    // We create the stateless strategy on the fly (or it could be cached)
    // and pass the call along with the shared state machine context.
    ImperativeBulkheadStrategy<T> strategy = new ImperativeBulkheadStrategy<>(name, maxWaitDuration);
    return strategy.decorate(call, stateMachine);
  }

  /**
   * Exposes the current number of in-flight calls for monitoring.
   */
  public int getConcurrentCalls() {
    return stateMachine.getConcurrentCalls();
  }

  /**
   * Exposes the current number of available permits for monitoring.
   */
  public int getAvailablePermits() {
    return stateMachine.getAvailablePermits();
  }
}
