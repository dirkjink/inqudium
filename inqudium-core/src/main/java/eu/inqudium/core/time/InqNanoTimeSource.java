package eu.inqudium.core.time;

import java.time.Instant;

/**
 * Functional interface for injectable time.
 *
 * <p>Every time-dependent algorithm in Inqudium core uses {@code InqNanoTimesource}
 * or {@code InqClock} instead of {@link Instant#now()}.
 * This ensures deterministic testability:
 * tests control time explicitly without {@code Thread.sleep()} or flakiness (ADR-016).
 *
 * <h2>Production usage</h2>
 * <pre>{@code
 * var config = CircuitBreakerConfig.builder()
 *     .nanoTime(InqNanoTimesource.system())   // default — can be omitted
 *     .build();
 * }</pre>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface InqNanoTimeSource {

  /**
   * Returns a clock backed by {@link Instant#now()}.
   *
   * @return the system clock
   */
  static InqNanoTimeSource system() {
    return System::nanoTime;
  }

  /**
   * Returns the current instant.
   *
   * @return the current time as seen by this clock
   */
  long now();
}
