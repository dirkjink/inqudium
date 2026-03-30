package eu.inqudium.bulkhead.imperative;

import eu.inqudium.bulkhead.Bulkhead;
import eu.inqudium.core.InqCall;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.BulkheadStateMachine;
import eu.inqudium.core.event.InqEventPublisher;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * The imperative facade combining the state machine and the execution strategy.
 *
 * @since 0.2.0
 */
public final class ImperativeBulkhead implements Bulkhead {

  private final String name;
  private final Duration maxWaitDuration;
  private final BulkheadStateMachine stateMachine;
  private final BulkheadConfig config;
  private final LongSupplier nanoTimeSource;

  // Package-private constructor forces the use of the Factory
  ImperativeBulkhead(String name, BulkheadConfig config, BulkheadStateMachine stateMachine) {
    this.name = name;
    this.maxWaitDuration = config.getMaxWaitDuration();
    this.config = config;
    this.stateMachine = stateMachine;
    // FIX #5: Store the nano-time source for passing to the strategy
    this.nanoTimeSource = config.getNanoTimeSource();
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public InqEventPublisher getEventPublisher() {
    return stateMachine.getEventPublisher();
  }

  @Override
  public BulkheadConfig getConfig() {
    return config;
  }

  @Override
  public <T> InqCall<T> decorate(InqCall<T> call) {
    // FIX #5: Pass nanoTimeSource to strategy for testable RTT measurement
    ImperativeBulkheadStrategy<T> strategy = new ImperativeBulkheadStrategy<>(
        name, maxWaitDuration, nanoTimeSource);
    return strategy.decorate(call, stateMachine);
  }

  public int getConcurrentCalls() {
    return stateMachine.getConcurrentCalls();
  }

  public int getAvailablePermits() {
    return stateMachine.getAvailablePermits();
  }
}
