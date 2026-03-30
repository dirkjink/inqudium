package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.bulkhead.BlockingBulkheadStateMachine;
import eu.inqudium.core.bulkhead.BulkheadParadigmStrategy;
import eu.inqudium.core.bulkhead.BulkheadStateMachine;
import eu.inqudium.core.bulkhead.InqBulkheadFullException;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * The imperative (synchronous/thread-blocking) implementation of the bulkhead strategy.
 */
public class ImperativeBulkheadStrategy<T> implements BulkheadParadigmStrategy<InqCall<T>, InqCall<T>> {

  private final Duration configuredTimeout;
  private final String bulkheadName;
  private final LongSupplier nanoTimeSource;

  ImperativeBulkheadStrategy(String bulkheadName, Duration configuredTimeout, LongSupplier nanoTimeSource) {
    this.bulkheadName = bulkheadName;
    this.configuredTimeout = configuredTimeout;
    this.nanoTimeSource = nanoTimeSource;
  }

  /**
   * FIX #4: Instead of silently returning the undecorated call when the state machine is
   * not a BlockingBulkheadStateMachine, we now throw an IllegalArgumentException. The
   * original else-branch returned the call without any bulkhead protection, meaning a
   * misconfigured factory would silently disable all resilience — a dangerous silent failure.
   */
  @Override
  public InqCall<T> decorate(InqCall<T> call, BulkheadStateMachine stateMachine) {
    if (!(stateMachine instanceof BlockingBulkheadStateMachine blockingStateMachine)) {
      throw new IllegalArgumentException(
          "ImperativeBulkheadStrategy requires a BlockingBulkheadStateMachine, "
              + "but received: " + stateMachine.getClass().getName()
              + ". This indicates a wiring error in the bulkhead factory.");
    }

    return call.withCallable(() -> {

      // Duty 1: Wait / Acquire (Imperative thread blocking)
      if (!blockingStateMachine.tryAcquire(call.callId(), configuredTimeout)) {
        throw new InqBulkheadFullException(
            call.callId(), bulkheadName, stateMachine.getConcurrentCalls(), stateMachine.getMaxConcurrentCalls());
      }

      // Duty 3 (Start): Measurement (using injectable time source)
      long startNanos = nanoTimeSource.getAsLong();
      Throwable businessError = null;

      try {
        // Execute the actual synchronous business logic
        return call.callable().call();
      } catch (Throwable t) {
        businessError = t;
        throw t;
      } finally {
        // Duty 3 (End): Measurement calculation
        Duration rtt = Duration.ofNanos(nanoTimeSource.getAsLong() - startNanos);

        // Duty 2: Guaranteed Release via try-finally
        stateMachine.releaseAndReport(call.callId(), rtt, businessError);
      }
    });
  }
}
