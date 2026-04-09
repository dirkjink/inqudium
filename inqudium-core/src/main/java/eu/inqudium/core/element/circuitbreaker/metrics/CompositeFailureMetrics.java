package eu.inqudium.core.element.circuitbreaker.metrics;

import java.util.List;
import java.util.function.LongFunction;

/**
 * Immutable implementation of a Composite FailureMetrics strategy using the
 * <a href="https://en.wikipedia.org/wiki/Composite_pattern">Composite pattern</a>.
 *
 * <h2>Algorithm Overview</h2>
 * <p>Wraps one or more {@link FailureMetrics} instances (delegates) and combines them
 * with <strong>OR-logic</strong>: the composite reports the threshold as reached if
 * <em>any single</em> delegate does. This allows operators to layer multiple detection
 * algorithms — for example, a fast-reacting {@link ConsecutiveFailuresMetrics} alongside
 * a longer-term {@link TimeBasedErrorRateMetrics} — so that the circuit trips on
 * whichever condition is satisfied first.
 *
 * <h2>Delegation Mechanics</h2>
 * <ul>
 *   <li><strong>{@link #recordSuccess(long)} / {@link #recordFailure(long)}:</strong>
 *       Every outcome is broadcast to <em>all</em> delegates. Each delegate independently
 *       updates its own state, and the resulting list of updated delegates is wrapped in
 *       a new {@code CompositeFailureMetrics}.</li>
 *   <li><strong>{@link #isThresholdReached(long)}:</strong> Short-circuits on the first
 *       delegate that returns {@code true} (fail-fast OR semantics).</li>
 *   <li><strong>{@link #reset(long)}:</strong> Resets every delegate and wraps the
 *       results in a new composite.</li>
 *   <li><strong>{@link #getTripReason(long)}:</strong> Collects trip reasons only from
 *       the delegates whose thresholds are currently reached, providing a precise
 *       diagnostic of which sub-strategies triggered the circuit.</li>
 * </ul>
 *
 * <h2>Immutability</h2>
 * <p>The delegate list is stored as an unmodifiable {@link List} (produced by
 * {@link List#of} or {@link java.util.stream.Stream#toList()}), and every mutation
 * operation returns a new composite instance. Combined with the immutability contract
 * of each delegate, this ensures full structural immutability.
 *
 * <h2>When to Use</h2>
 * <ul>
 *   <li>You want to combine multiple failure detection strategies so that the circuit
 *       trips on the <em>earliest</em> signal from any of them.</li>
 *   <li>Different failure patterns warrant different algorithms (e.g., burst detection
 *       via leaky bucket + sustained error rate via EWMA).</li>
 *   <li>You need a single {@link FailureMetrics} instance to pass through the circuit
 *       breaker's state machine, but want composite behavior internally.</li>
 * </ul>
 *
 * @param delegates the list of underlying {@link FailureMetrics} strategies; must contain
 *                  at least one element. The list is unmodifiable.
 */
public record CompositeFailureMetrics(List<FailureMetrics> delegates) implements FailureMetrics {

  /**
   * Varargs factory method for convenient construction.
   *
   * <p>Wraps the provided metrics in an unmodifiable list via {@link List#of}.
   *
   * @param metrics one or more {@link FailureMetrics} instances to compose
   * @return a new {@code CompositeFailureMetrics} combining all provided strategies
   * @throws IllegalArgumentException if {@code metrics} is null or empty
   */
  public static CompositeFailureMetrics of(FailureMetrics... metrics) {
    if (metrics == null || metrics.length == 0) {
      throw new IllegalArgumentException("At least one FailureMetrics instance must be provided");
    }
    return new CompositeFailureMetrics(List.of(metrics));
  }

  /**
   * Returns a factory that recreates the entire composite structure from scratch.
   *
   * <p>The returned factory captures each delegate's individual {@link FailureMetrics#metricsFactory()}
   * at creation time. When invoked with a nanosecond timestamp, it applies every captured factory
   * to produce fresh delegate instances and wraps them in a new {@code CompositeFailureMetrics}.
   *
   * <p>As an optimization, if only a single delegate is present, the factory returns that
   * delegate's metrics directly without the composite wrapper.
   *
   * @return a {@link LongFunction} that accepts a nanosecond timestamp and produces a
   *         pristine {@link FailureMetrics} instance mirroring this composite's structure
   */
  @Override
  public LongFunction<FailureMetrics> metricsFactory() {
    List<LongFunction<FailureMetrics>> factories = delegates.stream()
        .map(FailureMetrics::metricsFactory)
        .toList();

    return nowNanos -> {
      List<FailureMetrics> fresh = factories.stream()
          .map(f -> f.apply(nowNanos))
          .toList();
      return fresh.size() == 1 ? fresh.getFirst() : new CompositeFailureMetrics(fresh);
    };
  }

  /**
   * Records a successful call across <em>all</em> delegates.
   *
   * <p>Each delegate independently processes the success and returns its updated state.
   * If no delegate changed (all return the same identity), {@code this} is returned
   * to avoid unnecessary allocation.
   *
   * @param nowNanos the current timestamp in nanoseconds, forwarded to each delegate
   * @return a new {@code CompositeFailureMetrics} containing the updated delegates,
   *         or {@code this} if no delegate state changed
   */
  @Override
  public FailureMetrics recordSuccess(long nowNanos) {
    return applyToAll(nowNanos, Action.SUCCESS);
  }

  /**
   * Records a failed call across <em>all</em> delegates.
   *
   * <p>Each delegate independently processes the failure and returns its updated state.
   * If no delegate changed (all return the same identity), {@code this} is returned
   * to avoid unnecessary allocation.
   *
   * @param nowNanos the current timestamp in nanoseconds, forwarded to each delegate
   * @return a new {@code CompositeFailureMetrics} containing the updated delegates,
   *         or {@code this} if no delegate state changed
   */
  @Override
  public FailureMetrics recordFailure(long nowNanos) {
    return applyToAll(nowNanos, Action.FAILURE);
  }

  /**
   * Returns {@code true} if <em>any</em> delegate's threshold has been reached (OR-logic).
   *
   * <p>Iteration short-circuits on the first delegate that reports {@code true},
   * avoiding unnecessary evaluation of remaining delegates.
   *
   * @param nowNanos the current timestamp in nanoseconds, forwarded to each delegate
   * @return {@code true} if at least one delegate's threshold is reached
   */
  @Override
  public boolean isThresholdReached(long nowNanos) {
    for (int i = 0, size = delegates.size(); i < size; i++) {
      if (delegates.get(i).isThresholdReached(nowNanos)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Resets <em>all</em> delegates to their initial state and wraps the results
   * in a new composite instance.
   *
   * @param nowNanos the current timestamp, forwarded to each delegate for re-anchoring
   * @return a new {@code CompositeFailureMetrics} with all delegates reset
   */
  @Override
  public FailureMetrics reset(long nowNanos) {
    return applyToAll(nowNanos, Action.RESET);
  }

  /**
   * Produces a composite diagnostic message listing only the delegates whose thresholds
   * are currently reached.
   *
   * <p>Each triggering delegate's contribution is formatted as
   * {@code [SimpleClassName: delegate's trip reason]} and joined with commas. This gives
   * operators an immediate view of which sub-strategies caused the trip and their
   * individual diagnostics.
   *
   * @param nowNanos the current timestamp in nanoseconds, forwarded to each delegate
   * @return a formatted string such as
   * "Composite threshold reached. Triggering component(s):
   * [ConsecutiveFailuresMetrics: Received 5 failures in a row (Threshold: 5)]"
   */
  @Override
  public String getTripReason(long nowNanos) {
    // Single-pass StringBuilder avoids intermediate list and String.join
    StringBuilder sb = new StringBuilder("Composite threshold reached. Triggering component(s): ");
    boolean first = true;
    for (int i = 0, size = delegates.size(); i < size; i++) {
      FailureMetrics metric = delegates.get(i);
      if (metric.isThresholdReached(nowNanos)) {
        if (!first) {
          sb.append(", ");
        }
        sb.append('[')
            .append(metric.getClass().getSimpleName())
            .append(": ")
            .append(metric.getTripReason(nowNanos))
            .append(']');
        first = false;
      }
    }
    return sb.toString();
  }

  // ── internal helpers ──────────────────────────────────────────────────

  private enum Action { SUCCESS, FAILURE, RESET }

  /**
   * Applies the given action to every delegate. Returns {@code this} when all
   * delegates return the same identity (no state change), avoiding a new list
   * and a new composite allocation on the hot path.
   */
  private FailureMetrics applyToAll(long nowNanos, Action action) {
    int size = delegates.size();
    FailureMetrics[] updated = null; // allocated lazily only when a change is detected
    for (int i = 0; i < size; i++) {
      FailureMetrics original = delegates.get(i);
      FailureMetrics result = switch (action) {
        case SUCCESS -> original.recordSuccess(nowNanos);
        case FAILURE -> original.recordFailure(nowNanos);
        case RESET   -> original.reset(nowNanos);
      };
      if (result != original && updated == null) {
        // First change detected — back-fill array with previous unchanged delegates
        updated = new FailureMetrics[size];
        for (int j = 0; j < i; j++) {
          updated[j] = delegates.get(j);
        }
      }
      if (updated != null) {
        updated[i] = result;
      }
    }
    return updated == null ? this : new CompositeFailureMetrics(List.of(updated));
  }
}
