package eu.inqudium.core.element.ratelimiter.strategy;

import eu.inqudium.core.element.ratelimiter.RateLimitPermission;
import eu.inqudium.core.element.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.element.ratelimiter.ReservationResult;
import eu.inqudium.core.element.ratelimiter.strategy.SlidingWindowLogState;
import eu.inqudium.core.element.ratelimiter.strategy.SlidingWindowLogStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SlidingWindowLogStrategy — Exact Timestamp Rate Limiting")
class SlidingWindowLogStrategyTest {

  private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");

  private final SlidingWindowLogStrategy strategy = new SlidingWindowLogStrategy();

  private RateLimiterConfig<SlidingWindowLogState> defaultConfig() {
    return RateLimiterConfig.builder("sliding-log")
        .capacity(5)
        .refillPermits(5) // Not strictly used for math in log, but defines limit semantics
        .refillPeriod(Duration.ofMinutes(1)) // 1 minute window
        .withStrategy(new SlidingWindowLogStrategy())
        .build();
  }

  // ================================================================
  // Initial State
  // ================================================================

  @Nested
  @DisplayName("Initial State")
  class InitialState {

    @Test
    @DisplayName("should create an empty log state with zero epoch")
    void should_create_an_empty_log_state_with_zero_epoch() {
      // Given
      RateLimiterConfig<SlidingWindowLogState> config = defaultConfig();

      // When
      SlidingWindowLogState state = strategy.initial(config, NOW);

      // Then
      assertThat(state.timestampsMs()).isEmpty();
      assertThat(state.epoch()).isZero();
    }
  }

  // ================================================================
  // Permission Acquisition & Window Sliding
  // ================================================================

  @Nested
  @DisplayName("Permission Acquisition & Window Sliding")
  class PermissionAcquisition {

    @Test
    @DisplayName("should permit immediately when log has space and append exact timestamp")
    void should_permit_immediately_when_log_has_space_and_append_exact_timestamp() {
      // Given
      RateLimiterConfig<SlidingWindowLogState> config = defaultConfig();
      SlidingWindowLogState state = strategy.initial(config, NOW);

      // When
      RateLimitPermission<SlidingWindowLogState> result = strategy.tryAcquirePermissions(state, config, NOW, 2);

      // Then
      assertThat(result.permitted()).isTrue();
      assertThat(result.state().timestampsMs()).hasSize(2);
      assertThat(result.state().timestampsMs()).containsOnly(NOW.toEpochMilli());
      assertThat(result.waitDuration()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("should reject and calculate wait time until oldest timestamp expires")
    void should_reject_and_calculate_wait_time_until_oldest_timestamp_expires() {
      // Given - Log is full. Oldest timestamp was added 20 seconds ago.
      RateLimiterConfig<SlidingWindowLogState> config = defaultConfig(); // 1 minute window
      long[] fullLog = new long[]{
          NOW.minusSeconds(20).toEpochMilli(),
          NOW.minusSeconds(10).toEpochMilli(),
          NOW.toEpochMilli(),
          NOW.toEpochMilli(),
          NOW.toEpochMilli()
      };
      SlidingWindowLogState state = new SlidingWindowLogState(fullLog, 0L);

      // When - Ask for 1 permit
      RateLimitPermission<SlidingWindowLogState> result = strategy.tryAcquirePermissions(state, config, NOW, 1);

      // Then - The oldest (NOW - 20s) expires at NOW + 40s
      assertThat(result.permitted()).isFalse();
      assertThat(result.waitDuration()).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    @DisplayName("should evict expired timestamps from the log seamlessly")
    void should_evict_expired_timestamps_from_the_log_seamlessly() {
      // Given - Log has 3 items, but 2 are very old (expired)
      RateLimiterConfig<SlidingWindowLogState> config = defaultConfig();
      long[] mixedLog = new long[]{
          NOW.minusSeconds(100).toEpochMilli(), // expired
          NOW.minusSeconds(80).toEpochMilli(),  // expired
          NOW.minusSeconds(10).toEpochMilli()   // valid
      };
      SlidingWindowLogState state = new SlidingWindowLogState(mixedLog, 0L);

      // When - Check at NOW
      RateLimitPermission<SlidingWindowLogState> result = strategy.tryAcquirePermissions(state, config, NOW, 1);

      // Then - Only the valid one + the new one remain
      assertThat(result.permitted()).isTrue();
      assertThat(result.state().timestampsMs()).hasSize(2);
      assertThat(result.state().timestampsMs()[0]).isEqualTo(NOW.minusSeconds(10).toEpochMilli());
      assertThat(result.state().timestampsMs()[1]).isEqualTo(NOW.toEpochMilli());
    }
  }

  // ================================================================
  // Reservation
  // ================================================================

  @Nested
  @DisplayName("Permit Reservation")
  class PermitReservation {

    @Test
    @DisplayName("should append future timestamps for delayed reservations")
    void should_append_future_timestamps_for_delayed_reservations() {
      // Given - Full log
      RateLimiterConfig<SlidingWindowLogState> config = defaultConfig(); // 5 per min
      long[] fullLog = new long[5];
      java.util.Arrays.fill(fullLog, NOW.toEpochMilli());
      SlidingWindowLogState state = new SlidingWindowLogState(fullLog, 0L);

      // When - Ask for 2 permits, timeout is large
      ReservationResult<SlidingWindowLogState> result = strategy.reservePermissions(
          state, config, NOW, 2, Duration.ofMinutes(5));

      // Then
      assertThat(result.timedOut()).isFalse();
      assertThat(result.waitDuration()).isEqualTo(Duration.ofMinutes(1)); // All 5 expire in 1 min
      // Must contain the original 5 + 2 future execution timestamps
      assertThat(result.state().timestampsMs()).hasSize(7);

      // FIX: Instant kennt kein plusMinutes(), wir nutzen plus(Duration)
      assertThat(result.state().timestampsMs()[6]).isEqualTo(NOW.plus(Duration.ofMinutes(1)).toEpochMilli());
    }

    @Test
    @DisplayName("should time out if the oldest timestamp expiration exceeds the timeout")
    void should_time_out_if_the_oldest_timestamp_expiration_exceeds_the_timeout() {
      // Given - Full log
      RateLimiterConfig<SlidingWindowLogState> config = defaultConfig(); // 5 per min
      long[] fullLog = new long[5];
      java.util.Arrays.fill(fullLog, NOW.toEpochMilli());
      SlidingWindowLogState state = new SlidingWindowLogState(fullLog, 0L);

      // When - Require wait of 60s, but timeout is only 10s
      ReservationResult<SlidingWindowLogState> result = strategy.reservePermissions(
          state, config, NOW, 1, Duration.ofSeconds(10));

      // Then
      assertThat(result.timedOut()).isTrue();
      assertThat(result.state().timestampsMs()).hasSize(5); // Unchanged
    }
  }

  // ================================================================
  // Drain, Reset & Refund
  // ================================================================

  @Nested
  @DisplayName("Drain, Reset and Refund")
  class DrainResetRefund {

    @Test
    @DisplayName("should drain by filling the log with current timestamps up to capacity")
    void should_drain_by_filling_the_log_with_current_timestamps_up_to_capacity() {
      // Given
      RateLimiterConfig<SlidingWindowLogState> config = defaultConfig(); // capacity 5
      SlidingWindowLogState state = strategy.initial(config, NOW);

      // When
      SlidingWindowLogState drained = strategy.drain(state, config, NOW);

      // Then
      assertThat(drained.timestampsMs()).hasSize(5);
      assertThat(drained.timestampsMs()[0]).isEqualTo(NOW.toEpochMilli());
      assertThat(drained.epoch()).isEqualTo(1L);
    }

    @Test
    @DisplayName("should reset by completely emptying the log array")
    void should_reset_by_completely_emptying_the_log_array() {
      // Given
      RateLimiterConfig<SlidingWindowLogState> config = defaultConfig();
      SlidingWindowLogState state = new SlidingWindowLogState(new long[]{1L, 2L, 3L}, 0L);

      // When
      SlidingWindowLogState reset = strategy.reset(state, config, NOW);

      // Then
      assertThat(reset.timestampsMs()).isEmpty();
      assertThat(reset.epoch()).isEqualTo(1L);
    }

    @Test
    @DisplayName("should refund by removing the most recently added timestamps")
    void should_refund_by_removing_the_most_recently_added_timestamps() {
      // Given
      RateLimiterConfig<SlidingWindowLogState> config = defaultConfig();
      long[] log = new long[]{100L, 200L, 300L}; // 300L is the newest
      SlidingWindowLogState state = new SlidingWindowLogState(log, 0L);

      // When
      SlidingWindowLogState refunded = strategy.refund(state, config, 1);

      // Then
      assertThat(refunded.timestampsMs()).hasSize(2);
      assertThat(refunded.timestampsMs()).containsExactly(100L, 200L); // Oldest remain
    }
  }
}
