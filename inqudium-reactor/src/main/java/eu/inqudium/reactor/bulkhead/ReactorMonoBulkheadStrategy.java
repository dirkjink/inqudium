package eu.inqudium.reactor.bulkhead;

import eu.inqudium.core.bulkhead.BulkheadParadigmStrategy;
import eu.inqudium.core.bulkhead.BulkheadStateMachine;
import eu.inqudium.core.bulkhead.InqBulkheadFullException;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * The reactive implementation of the bulkhead strategy using Project Reactor.
 */
public class ReactorMonoBulkheadStrategy<T> implements BulkheadParadigmStrategy<Mono<T>, Mono<T>> {

  private final String bulkheadName;

  public ReactorMonoBulkheadStrategy(String bulkheadName) {
    this.bulkheadName = bulkheadName;
  }

  @Override
  public Mono<T> decorate(Mono<T> call, BulkheadStateMachine stateMachine) {
    // defer() ensures that the state check and time measurement only happen
    // when a consumer actually subscribes to the stream, not during assembly.
    return Mono.defer(() -> {

      // In a real implementation, the callId might be extracted from the Reactor Context
      String callId = "reactor-call";

      // Duty 1: Wait / Acquire (Non-blocking)
      // We strictly use the non-blocking method. We never park the Netty thread!
      if (!stateMachine.tryAcquireNonBlocking(callId)) {
        return Mono.error(new InqBulkheadFullException(
            callId, bulkheadName, stateMachine.getConcurrentCalls(), -1));
      }

      // Duty 3 (Start): Measurement
      // This is now safe because we are inside defer(). The clock starts ticking
      // exactly when the async execution begins.
      long startNanos = System.nanoTime();

      // An array is used to mutate the error state from within the reactive lambdas
      final Throwable[] capturedError = {null};

      return call
          // Capture business exceptions if they occur in the reactive stream
          .doOnError(error -> capturedError[0] = error)

          // Duty 2: Guaranteed Release via reative lifecycle hook
          // doFinally executes unconditionally (on success, error, or stream cancellation)
          .doFinally(signalType -> {
            // Duty 3 (End): Measurement calculation
            Duration rtt = Duration.ofNanos(System.nanoTime() - startNanos);

            // Hand the exact metrics and the permit back to the core state machine
            stateMachine.releaseAndReport(callId, rtt, capturedError[0]);
          });
    });
  }
}