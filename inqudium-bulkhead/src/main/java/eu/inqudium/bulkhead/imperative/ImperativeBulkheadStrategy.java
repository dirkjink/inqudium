package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.bulkhead.BulkheadParadigmStrategy;
import eu.inqudium.core.bulkhead.BulkheadStateMachine;
import eu.inqudium.core.bulkhead.InqBulkheadFullException;

import java.time.Duration;

/**
 * The imperative (synchronous/thread-blocking) implementation of the bulkhead strategy.
 */
public class ImperativeBulkheadStrategy<T> implements BulkheadParadigmStrategy<InqCall<T>, InqCall<T>> {

  private final Duration configuredTimeout;
  private final String bulkheadName;

  ImperativeBulkheadStrategy(String bulkheadName, Duration configuredTimeout) {
    this.bulkheadName = bulkheadName;
    this.configuredTimeout = configuredTimeout;
  }

  @Override
  public InqCall<T> decorate(InqCall<T> call, BulkheadStateMachine stateMachine) {
    return call.withCallable(() -> {

      // Duty 1: Wait / Acquire (Imperative thread blocking)
      if (!stateMachine.tryAcquireBlocking(call.callId(), configuredTimeout)) {
        throw new InqBulkheadFullException(
            call.callId(), bulkheadName, stateMachine.getConcurrentCalls(), stateMachine.getMaxConcurrentCalls());
        // Note: max limit tracking moved to StateMachine
      }

      // Duty 3 (Start): Measurement (Imperative clock)
      long startNanos = System.nanoTime();
      Throwable businessError = null;

      try {
        // Execute the actual synchronous business logic
        return call.callable().call();
      } catch (Throwable t) {
        businessError = t;
        throw t;
      } finally {
        // Duty 3 (End): Measurement calculation
        Duration rtt = Duration.ofNanos(System.nanoTime() - startNanos);

        // Duty 2: Guaranteed Release via try-finally
        stateMachine.releaseAndReport(call.callId(), rtt, businessError);
      }
    });
  }
}
