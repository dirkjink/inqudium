package eu.inqudium.rxjava3.bulkhead;

import eu.inqudium.core.bulkhead.BulkheadParadigmStrategy;
import eu.inqudium.core.bulkhead.BulkheadStateMachine;
import eu.inqudium.core.bulkhead.InqBulkheadFullException;
import io.reactivex.rxjava3.core.Single;

import java.time.Duration;

/**
 * The reactive implementation of the bulkhead strategy using RxJava 3 (Single).
 */
public class RxJavaSingleBulkheadStrategy<T> implements BulkheadParadigmStrategy<Single<T>, Single<T>> {

  private final String bulkheadName;

  public RxJavaSingleBulkheadStrategy(String bulkheadName) {
    this.bulkheadName = bulkheadName;
  }

  @Override
  public Single<T> decorate(Single<T> call, BulkheadStateMachine stateMachine) {
    // defer() ensures that the state check and time measurement only happen
    // when a consumer actually subscribes to the stream.
    return Single.defer(() -> {

      String callId = "rxjava-call";

      // Duty 1: Wait / Acquire (Non-blocking)
      // Never block the RxJava computation or io scheduler!
      if (!stateMachine.tryAcquireNonBlocking(callId)) {
        return Single.error(new InqBulkheadFullException(
            callId, bulkheadName, stateMachine.getConcurrentCalls(), -1));
      }

      // Duty 3 (Start): Measurement
      long startNanos = System.nanoTime();
      final Throwable[] capturedError = {null};

      return call
          // Capture business exceptions
          .doOnError(error -> capturedError[0] = error)

          // Duty 2: Guaranteed Release via RxJava lifecycle hook
          // doFinally executes on success, error, or dispose (cancellation)
          .doFinally(() -> {
            // Duty 3 (End): Measurement calculation
            Duration rtt = Duration.ofNanos(System.nanoTime() - startNanos);
            stateMachine.releaseAndReport(callId, rtt, capturedError[0]);
          });
    });
  }
}
