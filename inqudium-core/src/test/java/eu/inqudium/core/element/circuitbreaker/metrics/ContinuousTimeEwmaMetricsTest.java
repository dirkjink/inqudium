package eu.inqudium.core.element.circuitbreaker.metrics;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ContinuousTimeEwmaMetricsTest {

  private static final long NANOS_PER_SECOND = 1_000_000_000L;
  private static final long T0 = 100 * NANOS_PER_SECOND;

  // ======================== Initial State ========================

  @Nested
  class InitialState {

    @Test
    void should_start_with_zero_rate_and_zero_calls() {
      // Given / When
      var metrics = ContinuousTimeEwmaMetrics.initial(50, Duration.ofSeconds(5), 5, T0);

      // Then
      assertThat(metrics.currentRate()).isCloseTo(0.0, within(0.001));
      assertThat(metrics.callsCount()).isZero();
      assertThat(metrics.isThresholdReached(T0)).isFalse();
    }

    @Test
    void should_reject_minimum_calls_of_zero() {
      assertThatThrownBy(() -> ContinuousTimeEwmaMetrics.initial(50, Duration.ofSeconds(5), 0, T0))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ======================== Minimum Number of Calls ========================

  @Nested
  class MinimumNumberOfCalls {

    @Test
    void should_not_trip_before_minimum_calls_even_with_all_failures() {
      // Given — min=5, threshold=50%
      var metrics = ContinuousTimeEwmaMetrics.initial(50, Duration.ofSeconds(5), 5, T0);

      // When — 4 failures (one short)
      var updated = metrics;
      for (int i = 0; i < 4; i++) {
        updated = (ContinuousTimeEwmaMetrics) updated.recordFailure(T0 + i * NANOS_PER_SECOND);
      }

      // Then
      assertThat(updated.isThresholdReached(T0 + 4 * NANOS_PER_SECOND)).isFalse();
    }

    @Test
    void should_trip_once_minimum_calls_are_met() {
      // Given — min=3, threshold=50%, tau=100ms
      var metrics = ContinuousTimeEwmaMetrics.initial(50, Duration.ofMillis(100), 3, T0);

      // When — 3 failures spaced 100ms apart, starting 100ms after init
      long t1 = T0 + 100_000_000L;
      long t2 = t1 + 100_000_000L;
      long t3 = t2 + 100_000_000L;
      var updated = metrics.recordFailure(t1).recordFailure(t2).recordFailure(t3);

      // Then — rate should be well above 50% after 3 consecutive failures
      assertThat(updated.isThresholdReached(t3)).isTrue();
    }

  }

  // ======================== Time-Based Decay ========================

  @Nested
  class TimeBasedDecay {

    @Test
    void should_decay_failure_rate_over_time_without_new_samples() {
      // Given — threshold=50%, tau=1s, min=1
      var metrics = ContinuousTimeEwmaMetrics.initial(50, Duration.ofSeconds(1), 1, T0);

      // When — one failure slightly after init so the sample carries weight
      long failureTime = T0 + NANOS_PER_SECOND;
      var updated = metrics.recordFailure(failureTime);
      boolean reachedImmediately = updated.isThresholdReached(failureTime);
      boolean reachedLater = updated.isThresholdReached(failureTime + 10 * NANOS_PER_SECOND);

      // Then — should be above threshold right after failure, below after 10 * tau
      assertThat(reachedImmediately).isTrue();
      assertThat(reachedLater).isFalse();
    }

    @Test
    void should_decay_faster_with_shorter_time_constant() {
      // Given
      var shortTau = ContinuousTimeEwmaMetrics.initial(10, Duration.ofMillis(100), 1, T0);
      var longTau = ContinuousTimeEwmaMetrics.initial(10, Duration.ofSeconds(10), 1, T0);

      // When — both get one failure 1s after init so the sample carries weight
      long failureTime = T0 + NANOS_PER_SECOND;
      var shortUpdated = shortTau.recordFailure(failureTime);
      var longUpdated = longTau.recordFailure(failureTime);

      // Then — after another second, short tau should have decayed much more
      long evaluationTime = failureTime + NANOS_PER_SECOND;
      double shortRate = ((ContinuousTimeEwmaMetrics) shortUpdated.recordSuccess(evaluationTime)).currentRate();
      double longRate = ((ContinuousTimeEwmaMetrics) longUpdated.recordSuccess(evaluationTime)).currentRate();

      assertThat(shortRate).isLessThan(longRate);
    }

    @Test
    void should_not_trip_after_failures_have_fully_decayed() {
      // Given — tau=1s, threshold=50%, min=1
      var metrics = ContinuousTimeEwmaMetrics.initial(50, Duration.ofSeconds(1), 1, T0);

      // When — burst of failures, then wait 20 time constants
      var updated = metrics;
      for (int i = 0; i < 5; i++) updated = (ContinuousTimeEwmaMetrics) updated.recordFailure(T0 + i);
      long longAfter = T0 + 20 * NANOS_PER_SECOND;

      // Then
      assertThat(updated.isThresholdReached(longAfter)).isFalse();
    }
  }

  // ======================== Mixed Outcomes ========================

  @Nested
  class MixedOutcomes {

    @Test
    void should_lower_rate_when_successes_are_interleaved() {
      // Given — tau=100ms so each 100ms-spaced sample carries meaningful weight
      var metrics = ContinuousTimeEwmaMetrics.initial(50, Duration.ofMillis(100), 1, T0);

      // When — alternating F and S, 100ms apart
      var updated = metrics;
      for (int i = 0; i < 10; i++) {
        long t = T0 + i * 100_000_000L; // 100ms apart
        updated = (ContinuousTimeEwmaMetrics) ((i % 2 == 0)
            ? updated.recordFailure(t)
            : updated.recordSuccess(t));
      }

      // Then — rate should settle around 0.5 for evenly alternating outcomes
      double rate = ((ContinuousTimeEwmaMetrics) updated).currentRate();
      assertThat(rate).isBetween(0.2, 0.8);
    }

  }

  // ======================== Reset ========================

  @Nested
  class Resetting {

    @Test
    void should_return_to_zero_rate_and_zero_calls_after_reset() {
      // Given
      var metrics = ContinuousTimeEwmaMetrics.initial(50, Duration.ofSeconds(5), 3, T0);
      var filled = metrics.recordFailure(T0).recordFailure(T0 + 1).recordFailure(T0 + 2);

      // When
      long resetTime = T0 + 10 * NANOS_PER_SECOND;
      var afterReset = (ContinuousTimeEwmaMetrics) filled.reset(resetTime);

      // Then
      assertThat(afterReset.currentRate()).isCloseTo(0.0, within(0.001));
      assertThat(afterReset.callsCount()).isZero();
      assertThat(afterReset.lastUpdateNanos()).isEqualTo(resetTime);
    }
  }

  // ======================== Immutability ========================

  @Nested
  class Immutability {

    @Test
    void should_not_modify_original_when_recording() {
      // Given
      var original = ContinuousTimeEwmaMetrics.initial(50, Duration.ofSeconds(5), 3, T0);

      // When
      original.recordFailure(T0 + NANOS_PER_SECOND);

      // Then
      assertThat(original.currentRate()).isCloseTo(0.0, within(0.001));
      assertThat(original.callsCount()).isZero();
    }
  }

  // ======================== Trip Reason ========================

  @Nested
  class TripReason {

    @Test
    void should_include_rate_and_threshold_and_tau_in_reason() {
      // Given
      var metrics = ContinuousTimeEwmaMetrics.initial(50, Duration.ofSeconds(5), 1, T0);
      var tripped = metrics.recordFailure(T0);

      // When
      String reason = tripped.getTripReason(T0);

      // Then
      assertThat(reason)
          .containsIgnoringCase("EWMA")
          .contains("50")
          .containsIgnoringCase("Tau");
    }
  }
}
