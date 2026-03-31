package eu.inqudium.core.event;

/**
 * Functional interface for consuming events from an {@link InqEventPublisher}.
 *
 * <p>Consumers are registered per-element instance and receive only events from
 * that element. For cross-cutting event consumption, use {@link InqEventExporter}.
 *
 * <h2>Purpose</h2>
 * <p>Event consumers are intended for <strong>observation and analysis only</strong>
 * (logging, metrics collection, diagnostics). They must not trigger business logic,
 * external I/O, or any side effect that could affect the application's behavior.
 *
 * <h2>Execution model</h2>
 * <p>Consumers are invoked <strong>synchronously on the thread that publishes the
 * event</strong> — typically the application's calling thread inside the resilience
 * element. A slow or blocking consumer directly delays the protected code path and
 * all subsequent consumers.
 *
 * <p>If your use case requires I/O (e.g. sending metrics over the network), buffer
 * events internally and process them asynchronously on a separate thread. For
 * cross-cutting export to external systems, prefer {@link InqEventExporter}, which
 * enforces the same non-blocking contract explicitly.
 *
 * <h2>Thread safety and error handling</h2>
 * <p>Implementations must be thread-safe — events may be published from any thread
 * depending on the paradigm. Implementations must not throw — exceptions are caught
 * and logged but do not affect the element's operation.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface InqEventConsumer {

  /**
   * Called when an event is published.
   *
   * <p>Implementations must be thread-safe — events may be published from
   * any thread depending on the paradigm. Implementations must not throw —
   * exceptions are caught and logged but do not affect the element's operation.
   * <p>
   * Consumers run synchronously on the thread that publishes the event —
   * typically the application's calling thread. Blocking or long-running operations
   * (I/O, network calls, database writes) directly delay the protected code path.
   * For side effects that involve I/O, buffer internally and process asynchronously
   *
   * @param event the published event
   */
  void accept(InqEvent event);
}
