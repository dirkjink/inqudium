package eu.inqudium.core.element.ratelimiter.strategy;

import eu.inqudium.core.element.ratelimiter.RateLimitPermission;
import eu.inqudium.core.element.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.element.ratelimiter.ReservationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SlidingWindowCounterStrategy — Hybrid Counter Rate Limiting")
class SlidingWindowCounterStrategyTest {

    private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");

    private final SlidingWindowCounterStrategy strategy = new SlidingWindowCounterStrategy();

    private RateLimiterConfig<SlidingWindowCounterState> defaultConfig() {
        return RateLimiterConfig.builder("sliding-counter")
                .capacity(10)
                .refillPermits(10) // Window max limit
                .refillPeriod(Duration.ofMinutes(1)) // 1 minute window
                .withStrategy(new SlidingWindowCounterStrategy())
                .build();
    }

    // ================================================================
    // Initial State
    // ================================================================

    @Nested
    @DisplayName("Initial State")
    class InitialState {

        @Test
        @DisplayName("should create a state aligned to the current window size with zero counts")
        void should_create_a_state_aligned_to_the_current_window_size_with_zero_counts() {
            // Given
            RateLimiterConfig<SlidingWindowCounterState> config = defaultConfig();
            Instant unalignedNow = NOW.plusSeconds(25); // 25 seconds into the minute

            // When
            SlidingWindowCounterState state = strategy.initial(config, unalignedNow);

            // Then
            assertThat(state.currentWindowStart()).isEqualTo(NOW);
            assertThat(state.currentCount()).isZero();
            assertThat(state.previousCount()).isZero();
        }
    }

    // ================================================================
    // Permission Acquisition & Window Weighting
    // ================================================================

    @Nested
    @DisplayName("Permission Acquisition & Window Weighting")
    class PermissionAcquisition {

        @Test
        @DisplayName("should calculate estimated usage based on previous window weight")
        void should_calculate_estimated_usage_based_on_previous_window_weight() {
            // Given - Previous window had exactly 10 requests. Current has 0.
            RateLimiterConfig<SlidingWindowCounterState> config = defaultConfig();
            SlidingWindowCounterState state = new SlidingWindowCounterState(NOW, 10, 0, 0L);

            // When - We are exactly at 30 seconds (50% through the window)
            // The estimated usage should be: 0 (current) + 10 * 0.5 (remaining weight) = 5
            Instant queryTime = NOW.plusSeconds(30);
            RateLimitPermission<SlidingWindowCounterState> result = strategy.tryAcquirePermissions(
                    state, config, queryTime, 5); // Requesting 5 should exactly hit the limit of 10

            // Then
            assertThat(result.permitted()).isTrue();
            assertThat(result.state().currentCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("should reject if estimated usage plus requested permits exceed capacity")
        void should_reject_if_estimated_usage_plus_requested_permits_exceed_capacity() {
            // Given - Previous window had 10 requests. Current has 0.
            RateLimiterConfig<SlidingWindowCounterState> config = defaultConfig();
            SlidingWindowCounterState state = new SlidingWindowCounterState(NOW, 10, 0, 0L);

            // When - We are at 15 seconds (25% through). Previous weight is 75%.
            // Estimated: 10 * 0.75 = 7.5 -> rounded to 7. Requesting 4 makes it 11 (> 10).
            Instant queryTime = NOW.plusSeconds(15);
            RateLimitPermission<SlidingWindowCounterState> result = strategy.tryAcquirePermissions(
                    state, config, queryTime, 4);

            // Then
            assertThat(result.permitted()).isFalse();
            assertThat(result.waitDuration()).isGreaterThan(Duration.ZERO);
        }

        @Test
        @DisplayName("should transition correctly when a full window passes")
        void should_transition_correctly_when_a_full_window_passes() {
            // Given
            RateLimiterConfig<SlidingWindowCounterState> config = defaultConfig();
            SlidingWindowCounterState state = new SlidingWindowCounterState(NOW, 5, 8, 0L);

            // When - Exactly one window later
            Instant queryTime = NOW.plus(Duration.ofMinutes(1));
            RateLimitPermission<SlidingWindowCounterState> result = strategy.tryAcquirePermissions(
                    state, config, queryTime, 1);

            // Then - 'current' (8) becomes 'previous'. Old 'previous' (5) is dropped.
            assertThat(result.permitted()).isTrue();
            assertThat(result.state().previousCount()).isEqualTo(8);
            assertThat(result.state().currentCount()).isEqualTo(1); // the newly acquired permit
            assertThat(result.state().currentWindowStart()).isEqualTo(queryTime);
        }

        @Test
        @DisplayName("should drop all counts if multiple windows pass without activity")
        void should_drop_all_counts_if_multiple_windows_pass_without_activity() {
            // Given
            RateLimiterConfig<SlidingWindowCounterState> config = defaultConfig();
            SlidingWindowCounterState state = new SlidingWindowCounterState(NOW, 10, 10, 0L);

            // When - 2 minutes pass (skipping one entire window)
            Instant queryTime = NOW.plus(Duration.ofMinutes(2));
            RateLimitPermission<SlidingWindowCounterState> result = strategy.tryAcquirePermissions(
                    state, config, queryTime, 1);

            // Then
            assertThat(result.permitted()).isTrue();
            assertThat(result.state().previousCount()).isZero();
            assertThat(result.state().currentCount()).isEqualTo(1);
        }
    }

    // ================================================================
    // Reservation
    // ================================================================

    @Nested
    @DisplayName("Permit Reservation")
    class PermitReservation {

        @Test
        @DisplayName("should reserve with wait time calculated from fading previous window weight")
        void should_reserve_with_wait_time_calculated_from_fading_previous_window_weight() {
            // Given - Previous 10, current 0. Capacity 10.
            RateLimiterConfig<SlidingWindowCounterState> config = defaultConfig(); // 60s window
            SlidingWindowCounterState state = new SlidingWindowCounterState(NOW, 10, 0, 0L);

            // When - We ask for 5 permits immediately at the start of the window.
            // Estimated is currently 10. To allow 5, the previous weight must drop to 5.
            // This happens exactly at 50% of the window (30 seconds).
            ReservationResult<SlidingWindowCounterState> result = strategy.reservePermissions(
                    state, config, NOW, 5, Duration.ofMinutes(2));

            // Then
            assertThat(result.timedOut()).isFalse();
            assertThat(result.waitDuration()).isEqualTo(Duration.ofSeconds(30));
            assertThat(result.state().currentCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("should delay into the next window if current count is too high")
        void should_delay_into_the_next_window_if_current_count_is_too_high() {
            // Given - Current window is maxed out.
            RateLimiterConfig<SlidingWindowCounterState> config = defaultConfig();
            SlidingWindowCounterState state = new SlidingWindowCounterState(NOW, 0, 10, 0L);

            // When - Ask for 5 permits, 10 seconds into the current window
            Instant queryTime = NOW.plusSeconds(10);
            ReservationResult<SlidingWindowCounterState> result = strategy.reservePermissions(
                    state, config, queryTime, 5, Duration.ofMinutes(2));

            // Then - We must wait 50s for the window to end. In the NEXT window, 'current' (10) becomes 'previous'.
            // To allow 5 permits next window, previous weight must fade to 5.
            // That takes another 30 seconds into the next window. Total wait: 50s + 30s = 80s.
            assertThat(result.timedOut()).isFalse();
            assertThat(result.waitDuration()).isEqualTo(Duration.ofSeconds(80));
        }

        @Test
        @DisplayName("should time out if calculated fading wait exceeds timeout")
        void should_time_out_if_calculated_fading_wait_exceeds_timeout() {
            // Given
            RateLimiterConfig<SlidingWindowCounterState> config = defaultConfig();
            SlidingWindowCounterState state = new SlidingWindowCounterState(NOW, 10, 0, 0L);

            // When - Requires 30s wait, but timeout is 10s
            ReservationResult<SlidingWindowCounterState> result = strategy.reservePermissions(
                    state, config, NOW, 5, Duration.ofSeconds(10));

            // Then
            assertThat(result.timedOut()).isTrue();
            assertThat(result.state().currentCount()).isZero(); // No state change
        }
    }

    // ================================================================
    // Drain, Reset & Refund
    // ================================================================

    @Nested
    @DisplayName("Drain, Reset and Refund")
    class DrainResetRefund {

        @Test
        @DisplayName("should drain by maximizing current count")
        void should_drain_by_maximizing_current_count() {
            // Given
            RateLimiterConfig<SlidingWindowCounterState> config = defaultConfig();
            SlidingWindowCounterState state = strategy.initial(config, NOW);

            // When
            SlidingWindowCounterState drained = strategy.drain(state, config, NOW);

            // Then
            assertThat(drained.currentCount()).isEqualTo(10);
            assertThat(drained.epoch()).isEqualTo(1L);
        }

        @Test
        @DisplayName("should reset both previous and current counts")
        void should_reset_both_previous_and_current_counts() {
            // Given
            RateLimiterConfig<SlidingWindowCounterState> config = defaultConfig();
            SlidingWindowCounterState state = new SlidingWindowCounterState(NOW, 5, 8, 0L);

            // When
            SlidingWindowCounterState reset = strategy.reset(state, config, NOW);

            // Then
            assertThat(reset.currentCount()).isZero();
            assertThat(reset.previousCount()).isZero();
            assertThat(reset.epoch()).isEqualTo(1L);
        }

        @Test
        @DisplayName("should refund by reducing the current count without going below zero")
        void should_refund_by_reducing_the_current_count_without_going_below_zero() {
            // Given
            RateLimiterConfig<SlidingWindowCounterState> config = defaultConfig();
            SlidingWindowCounterState state = new SlidingWindowCounterState(NOW, 0, 5, 0L);

            // When
            SlidingWindowCounterState refunded = strategy.refund(state, config, 3);
            SlidingWindowCounterState overRefunded = strategy.refund(state, config, 10);

            // Then
            assertThat(refunded.currentCount()).isEqualTo(2);
            assertThat(overRefunded.currentCount()).isZero();
        }
    }
}
