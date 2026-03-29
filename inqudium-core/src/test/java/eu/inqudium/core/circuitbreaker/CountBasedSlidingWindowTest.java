package eu.inqudium.core.circuitbreaker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("CountBasedSlidingWindow")
class CountBasedSlidingWindowTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final long SLOW_THRESHOLD_NANOS = 3_000_000_000L; // 3 seconds

  @Nested
  @DisplayName("Empty window")
  class EmptyWindow {

    @Test
    void should_return_zero_rates_when_no_calls_recorded() {
      // Given
      var window = new CountBasedSlidingWindow(10, SLOW_THRESHOLD_NANOS);

      // When
      var snapshot = window.snapshot();

      // Then
      assertThat(snapshot.totalCalls()).isZero();
      assertThat(snapshot.failureRate()).isZero();
      assertThat(snapshot.slowCallRate()).isZero();
    }

    @Test
    void should_not_have_minimum_calls_when_empty() {
      // Given
      var window = new CountBasedSlidingWindow(10, SLOW_THRESHOLD_NANOS);

      // When
      var snapshot = window.snapshot();

      // Then
      assertThat(snapshot.hasMinimumCalls(1)).isFalse();
    }
  }

  @Nested
  @DisplayName("Recording outcomes")
  class RecordingOutcomes {

    @Test
    void should_calculate_fifty_percent_failure_rate_with_equal_successes_and_failures() {
      // Given
      var window = new CountBasedSlidingWindow(10, SLOW_THRESHOLD_NANOS);

      // When
      window.record(CallOutcome.success(1_000_000L, NOW));
      window.record(CallOutcome.failure(1_000_000L, NOW));
      var snapshot = window.record(CallOutcome.success(1_000_000L, NOW));

      // Then
      assertThat(snapshot.totalCalls()).isEqualTo(3);
      assertThat(snapshot.failedCalls()).isEqualTo(1);
      assertThat(snapshot.failureRate()).isCloseTo(33.33f, within(0.1f));
    }

    @Test
    void should_calculate_hundred_percent_failure_rate_when_all_calls_fail() {
      // Given
      var window = new CountBasedSlidingWindow(5, SLOW_THRESHOLD_NANOS);

      // When
      for (int i = 0; i < 5; i++) {
        window.record(CallOutcome.failure(1_000_000L, NOW));
      }
      var snapshot = window.snapshot();

      // Then
      assertThat(snapshot.failureRate()).isEqualTo(100.0f);
      assertThat(snapshot.totalCalls()).isEqualTo(5);
    }

    @Test
    void should_track_slow_calls_independently_of_success_or_failure() {
      // Given
      var window = new CountBasedSlidingWindow(10, SLOW_THRESHOLD_NANOS);

      // When — 2 slow successes, 1 fast failure
      window.record(CallOutcome.success(4_000_000_000L, NOW)); // slow success
      window.record(CallOutcome.success(5_000_000_000L, NOW)); // slow success
      window.record(CallOutcome.failure(1_000_000L, NOW));     // fast failure
      var snapshot = window.snapshot();

      // Then
      assertThat(snapshot.slowCalls()).isEqualTo(2);
      assertThat(snapshot.failedCalls()).isEqualTo(1);
      assertThat(snapshot.slowCallRate()).isCloseTo(66.67f, within(0.1f));
    }
  }

  @Nested
  @DisplayName("Circular buffer eviction")
  class CircularBufferEviction {

    @Test
    void should_evict_oldest_entry_when_buffer_is_full() {
      // Given — window of size 3
      var window = new CountBasedSlidingWindow(3, SLOW_THRESHOLD_NANOS);

      // When — fill with 3 failures, then add 3 successes
      window.record(CallOutcome.failure(1_000_000L, NOW));
      window.record(CallOutcome.failure(1_000_000L, NOW));
      window.record(CallOutcome.failure(1_000_000L, NOW));
      // Now buffer is [F, F, F] — 100% failure

      window.record(CallOutcome.success(1_000_000L, NOW)); // evicts oldest F
      window.record(CallOutcome.success(1_000_000L, NOW)); // evicts next F
      var snapshot = window.record(CallOutcome.success(1_000_000L, NOW)); // evicts last F

      // Then — all failures evicted, only successes remain
      assertThat(snapshot.totalCalls()).isEqualTo(3);
      assertThat(snapshot.failedCalls()).isZero();
      assertThat(snapshot.failureRate()).isZero();
    }

    @Test
    void should_correctly_evict_slow_call_counts_on_rotation() {
      // Given — window of size 2
      var window = new CountBasedSlidingWindow(2, SLOW_THRESHOLD_NANOS);

      // When
      window.record(CallOutcome.success(4_000_000_000L, NOW)); // slow
      window.record(CallOutcome.success(1_000_000L, NOW));     // fast
      // Buffer: [slow, fast] — 50% slow

      var snapshot = window.record(CallOutcome.success(1_000_000L, NOW)); // evicts slow
      // Buffer: [fast, fast] — 0% slow

      // Then
      assertThat(snapshot.slowCalls()).isZero();
    }
  }

  @Nested
  @DisplayName("Reset")
  class Reset {

    @Test
    void should_clear_all_counters_on_reset() {
      // Given
      var window = new CountBasedSlidingWindow(5, SLOW_THRESHOLD_NANOS);
      window.record(CallOutcome.failure(4_000_000_000L, NOW));
      window.record(CallOutcome.failure(4_000_000_000L, NOW));

      // When
      window.reset();
      var snapshot = window.snapshot();

      // Then
      assertThat(snapshot.totalCalls()).isZero();
      assertThat(snapshot.failedCalls()).isZero();
      assertThat(snapshot.slowCalls()).isZero();
    }
  }
}
