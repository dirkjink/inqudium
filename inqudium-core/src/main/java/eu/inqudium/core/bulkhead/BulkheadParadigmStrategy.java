package eu.inqudium.core.bulkhead;

/**
 * Defines the execution strategy for a specific programming paradigm (e.g., Imperative, Reactor, Coroutines).
 * * <h2>The 3 Paradigm Duties (Contract)</h2>
 * Any implementation of this strategy MUST guarantee the following:
 * <ul>
 * <li><strong>1. Wait/Acquire:</strong> Must use the appropriate waiting mechanism for its ecosystem
 * (e.g., thread-blocking for imperative, deferred subscription for reactive, suspension for coroutines).</li>
 * <li><strong>2. Guaranteed Release:</strong> Must guarantee that {@link BulkheadStateMachine#releaseAndReport}
 * is called exactly once if a permit was acquired, regardless of success, failure, or cancellation.</li>
 * <li><strong>3. Measurement:</strong> Must measure the exact execution duration (RTT) natively within
 * the paradigm's lifecycle (e.g., {@code System.nanoTime()} for imperative, context signals for reactive).</li>
 * </ul>
 * * @param <CALL> the input wrapper (e.g., InqCall, Mono, Flow)
 *
 * @param <RESULT> the decorated output wrapper
 */
public interface BulkheadParadigmStrategy<CALL, RESULT> {

  /**
   * Decorates the given call with the bulkhead logic specific to this paradigm.
   * * @param call the target call to decorate
   *
   * @param stateMachine the bulkhead state to use for permits and events
   * @return the decorated, protected call
   */
  RESULT decorate(CALL call, BulkheadStateMachine stateMachine);
}
