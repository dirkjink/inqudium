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

@DisplayName("FixedWindowStrategy — Fixed Window Counter Rate Limiting")
class FixedBackoffStrategyWindowStrategyTest {

    private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");

    private final FixedWindowStrategy strategy = new FixedWindowStrategy();

    private RateLimiterConfig<FixedWindowState> defaultConfig() {
        return RateLimiterConfig.builder("fixed")
                .capacity(10)
                .refillPermits(10) // Semantically limits per window
                .refillPeriod(Duration.ofMinutes(1)) // 1 minute window
                .withStrategy(new FixedWindowStrategy())
                .build();
    }

    // ================================================================
    // Initial State
    // ================================================================

    @Nested
    @DisplayName("Initial State")
    class InitialState {

        @Test
        @DisplayName("A freshly created state should have zero used permits and an aligned window start")
        void a_freshly_created_state_should_have_zero_used_permits_and_an_aligned_window_start() {
            // Given
            RateLimiterConfig<FixedWindowState> config = defaultConfig();
            Instant unalignedNow = NOW.plusSeconds(15);

            // When
            FixedWindowState state = strategy.initial(config, unalignedNow);

            // Then
            assertThat(state.usedPermits()).isZero();
            assertThat(state.epoch()).isZero();
            assertThat(state.windowStart()).isEqualTo(NOW); // Aligned to the minute boundary
        }
    }

    // ================================================================
    // Permission Acquisition
    // ================================================================

    @Nested
    @DisplayName("Permission Acquisition")
    class PermissionAcquisition {

        @Test
        @DisplayName("Should permit immediately when requested permits are within the window capacity")
        void should_permit_immediately_when_requested_permits_are_within_the_window_capacity() {
            // Given
            RateLimiterConfig<FixedWindowState> config = defaultConfig();
            FixedWindowState state = strategy.initial(config, NOW);

            // When
            RateLimitPermission<FixedWindowState> result = strategy.tryAcquirePermissions(state, config, NOW, 5);

            // Then
            assertThat(result.permitted()).isTrue();
            assertThat(result.state().usedPermits()).isEqualTo(5);
            assertThat(result.waitDuration()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("Should reject and calculate accurate wait time when the window limit is reached")
        void should_reject_and_calculate_accurate_wait_time_when_the_window_limit_is_reached() {
            // Given
            RateLimiterConfig<FixedWindowState> config = defaultConfig(); // 10 per minute
            FixedWindowState state = new FixedWindowState(8, NOW, 0L);

            // When - Ask for 5 permits 30 seconds into the current window
            RateLimitPermission<FixedWindowState> result = strategy.tryAcquirePermissions(
                    state, config, NOW.plusSeconds(30), 5);

            // Then
            assertThat(result.permitted()).isFalse();
            // Must wait for the next window which starts at NOW + 60 seconds.
            // Current time is NOW + 30 seconds -> Remaining wait is 30 seconds.
            assertThat(result.waitDuration()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("Should implicitly reset used permits when entering a new window")
        void should_implicitly_reset_used_permits_when_entering_a_new_window() {
            // Given
            RateLimiterConfig<FixedWindowState> config = defaultConfig();
            FixedWindowState state = new FixedWindowState(10, NOW, 0L); // Fully exhausted

            // When - Accessing exactly one window later
            Instant nextWindow = NOW.plus(Duration.ofMinutes(1));
            RateLimitPermission<FixedWindowState> result = strategy.tryAcquirePermissions(
                    state, config, nextWindow, 2);

            // Then
            assertThat(result.permitted()).isTrue();
            assertThat(result.state().usedPermits()).isEqualTo(2); // Starts fresh in the new window
            assertThat(result.state().windowStart()).isEqualTo(nextWindow);
        }
    }

    // ================================================================
    // Permit Reservation
    // ================================================================

    @Nested
    @DisplayName("Permit Reservation")
    class PermitReservation {

        @Test
        @DisplayName("Should return an immediate reservation if permits are available in the current window")
        void should_return_an_immediate_reservation_if_permits_are_available_in_the_current_window() {
            // Given
            RateLimiterConfig<FixedWindowState> config = defaultConfig();
            FixedWindowState state = strategy.initial(config, NOW);

            // When
            ReservationResult<FixedWindowState> result = strategy.reservePermissions(
                    state, config, NOW, 3, Duration.ofSeconds(10));

            // Then
            assertThat(result.timedOut()).isFalse();
            assertThat(result.waitDuration()).isEqualTo(Duration.ZERO);
            assertThat(result.state().usedPermits()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should delay the reservation until the next window if the current is exhausted but within timeout")
        void should_delay_the_reservation_until_the_next_window_if_the_current_is_exhausted_but_within_timeout() {
            // Given
            RateLimiterConfig<FixedWindowState> config = defaultConfig(); // 1 minute window
            FixedWindowState state = new FixedWindowState(10, NOW, 0L);

            // When - Timeout is 2 minutes, 10 seconds have passed in the current window
            Instant queryTime = NOW.plusSeconds(10);
            ReservationResult<FixedWindowState> result = strategy.reservePermissions(
                    state, config, queryTime, 5, Duration.ofMinutes(2));

            // Then - Wait for the remaining 50 seconds of the current window
            assertThat(result.timedOut()).isFalse();
            assertThat(result.waitDuration()).isEqualTo(Duration.ofSeconds(50));
            assertThat(result.state().usedPermits()).isEqualTo(15); // Booked into the next window
        }

        @Test
        @DisplayName("Should time out when the required window boundary is beyond the allowed timeout")
        void should_time_out_when_the_required_window_boundary_is_beyond_the_allowed_timeout() {
            // Given
            RateLimiterConfig<FixedWindowState> config = defaultConfig();
            FixedWindowState state = new FixedWindowState(10, NOW, 0L); // Current exhausted

            // When - Need to wait until the end of the window (60s), but timeout is only 10s
            ReservationResult<FixedWindowState> result = strategy.reservePermissions(
                    state, config, NOW, 5, Duration.ofSeconds(10));

            // Then
            assertThat(result.timedOut()).isTrue();
            assertThat(result.state().usedPermits()).isEqualTo(10); // State preserved
        }
    }

    // ================================================================
    // Drain, Reset & Refund
    // ================================================================

    @Nested
    @DisplayName("Drain, Reset and Refund")
    class DrainResetRefund {

        @Test
        @DisplayName("Should drain the current window by maximizing used permits and incrementing the epoch")
        void should_drain_the_current_window_by_maximizing_used_permits_and_incrementing_the_epoch() {
            // Given
            RateLimiterConfig<FixedWindowState> config = defaultConfig();
            FixedWindowState state = strategy.initial(config, NOW);

            // When
            FixedWindowState drained = strategy.drain(state, config, NOW);

            // Then
            assertThat(drained.usedPermits()).isEqualTo(10);
            assertThat(drained.epoch()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should reset the window by clearing used permits and incrementing the epoch")
        void should_reset_the_window_by_clearing_used_permits_and_incrementing_the_epoch() {
            // Given
            RateLimiterConfig<FixedWindowState> config = defaultConfig();
            FixedWindowState state = new FixedWindowState(8, NOW, 0L);

            // When
            FixedWindowState reset = strategy.reset(state, config, NOW);

            // Then
            assertThat(reset.usedPermits()).isZero();
            assertThat(reset.epoch()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should correctly refund used permits without falling below zero")
        void should_correctly_refund_used_permits_without_falling_below_zero() {
            // Given
            RateLimiterConfig<FixedWindowState> config = defaultConfig();
            FixedWindowState state = new FixedWindowState(5, NOW, 0L);

            // When
            FixedWindowState refunded = strategy.refund(state, config, 3);
            FixedWindowState overRefunded = strategy.refund(state, config, 10);

            // Then
            assertThat(refunded.usedPermits()).isEqualTo(2);
            assertThat(overRefunded.usedPermits()).isZero(); // Clamped at 0
        }
    }
}
