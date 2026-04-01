package eu.inqudium.core.element.ratelimiter.strategy;

import eu.inqudium.core.element.ratelimiter.RateLimitPermission;
import eu.inqudium.core.element.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.element.ratelimiter.ReservationResult;
import eu.inqudium.core.element.ratelimiter.strategy.TokenBucketState;
import eu.inqudium.core.element.ratelimiter.strategy.TokenBucketStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TokenBucketStrategy — Functional Rate Limiting Strategy")
class TokenBucketStrategyTest {

  private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");

  private final TokenBucketStrategy strategy = new TokenBucketStrategy();

  private RateLimiterConfig<TokenBucketState> defaultConfig() {
    return RateLimiterConfig.builder("token-bucket")
        .capacity(5)
        .refillPermits(5)
        .refillPeriod(Duration.ofSeconds(1))
        .build();
  }

  // ================================================================
  // Initial State
  // ================================================================

  @Nested
  @DisplayName("Initial State")
  class InitialState {

    @Test
    @DisplayName("A freshly created state should have a full bucket and epoch zero")
    void a_freshly_created_state_should_have_a_full_bucket_and_epoch_zero() {
      // Given
      RateLimiterConfig<TokenBucketState> config = defaultConfig();

      // When
      TokenBucketState state = strategy.initial(config, NOW);

      // Then
      assertThat(state.availablePermits()).isEqualTo(5);
      assertThat(state.epoch()).isZero();
      assertThat(state.lastRefillTime()).isEqualTo(NOW);
    }
  }

  // ================================================================
  // Permission Acquisition
  // ================================================================

  @Nested
  @DisplayName("Permission Acquisition")
  class PermissionAcquisition {

    @Test
    @DisplayName("Should permit immediately when the requested permits are within the available capacity")
    void should_permit_immediately_when_the_requested_permits_are_within_the_available_capacity() {
      // Given
      RateLimiterConfig<TokenBucketState> config = defaultConfig();
      TokenBucketState state = strategy.initial(config, NOW);

      // When
      RateLimitPermission<TokenBucketState> result = strategy.tryAcquirePermissions(state, config, NOW, 3);

      // Then
      assertThat(result.permitted()).isTrue();
      assertThat(result.state().availablePermits()).isEqualTo(2); // 5 capacity - 3 consumed
      assertThat(result.waitDuration()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("Should reject and estimate wait time when the bucket does not have enough permits")
    void should_reject_and_estimate_wait_time_when_the_bucket_does_not_have_enough_permits() {
      // Given
      RateLimiterConfig<TokenBucketState> config = defaultConfig(); // 5 per sec
      TokenBucketState state = new TokenBucketState(2, NOW, 0L); // Only 2 permits left

      // When
      RateLimitPermission<TokenBucketState> result = strategy.tryAcquirePermissions(state, config, NOW, 4);

      // Then
      assertThat(result.permitted()).isFalse();
      assertThat(result.waitDuration()).isEqualTo(Duration.ofSeconds(1)); // Needs 1 refill cycle
    }

    @Test
    @DisplayName("Should refill correctly over time before evaluating the acquisition")
    void should_refill_correctly_over_time_before_evaluating_the_acquisition() {
      // Given
      RateLimiterConfig<TokenBucketState> config = defaultConfig();
      TokenBucketState state = new TokenBucketState(0, NOW, 0L);

      // When
      Instant queryTime = NOW.plusSeconds(2); // Two refill cycles passed -> 10 permits added, capped at 5
      RateLimitPermission<TokenBucketState> result = strategy.tryAcquirePermissions(state, config, queryTime, 4);

      // Then
      assertThat(result.permitted()).isTrue();
      assertThat(result.state().availablePermits()).isEqualTo(1); // Capped at 5, then 4 consumed
    }

    @Test
    @DisplayName("Should reject requests that exceed the absolute maximum capacity of the bucket")
    void should_reject_requests_that_exceed_the_absolute_maximum_capacity_of_the_bucket() {
      // Given
      RateLimiterConfig<TokenBucketState> config = defaultConfig(); // max capacity is 5
      TokenBucketState state = strategy.initial(config, NOW);

      // When / Then
      assertThatThrownBy(() -> strategy.tryAcquirePermissions(state, config, NOW, 10))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ================================================================
  // Permit Reservation
  // ================================================================

  @Nested
  @DisplayName("Permit Reservation")
  class PermitReservation {

    @Test
    @DisplayName("Should return an immediate reservation when sufficient permits are available")
    void should_return_an_immediate_reservation_when_sufficient_permits_are_available() {
      // Given
      RateLimiterConfig<TokenBucketState> config = defaultConfig();
      TokenBucketState state = strategy.initial(config, NOW);

      // When
      ReservationResult<TokenBucketState> result = strategy.reservePermissions(
          state, config, NOW, 2, Duration.ofSeconds(5));

      // Then
      assertThat(result.timedOut()).isFalse();
      assertThat(result.waitDuration()).isEqualTo(Duration.ZERO);
      assertThat(result.state().availablePermits()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should return a delayed reservation creating debt when the bucket is empty but timeout suffices")
    void should_return_a_delayed_reservation_creating_debt_when_the_bucket_is_empty_but_timeout_suffices() {
      // Given
      RateLimiterConfig<TokenBucketState> config = defaultConfig();
      TokenBucketState state = new TokenBucketState(0, NOW, 0L);

      // When
      ReservationResult<TokenBucketState> result = strategy.reservePermissions(
          state, config, NOW, 3, Duration.ofSeconds(5));

      // Then
      assertThat(result.timedOut()).isFalse();
      assertThat(result.waitDuration()).isEqualTo(Duration.ofSeconds(1)); // Needs 1 refill cycle
      assertThat(result.state().availablePermits()).isEqualTo(-3); // Creates debt
    }

    @Test
    @DisplayName("Should time out and preserve state when the estimated wait exceeds the provided timeout")
    void should_time_out_and_preserve_state_when_the_estimated_wait_exceeds_the_provided_timeout() {
      // Given
      RateLimiterConfig<TokenBucketState> config = defaultConfig(); // 5 permits per sec
      TokenBucketState state = new TokenBucketState(0, NOW, 0L);

      // When - Ask for 5 permits, which requires 1 sec wait, but timeout is 100ms
      ReservationResult<TokenBucketState> result = strategy.reservePermissions(
          state, config, NOW, 5, Duration.ofMillis(100));

      // Then
      assertThat(result.timedOut()).isTrue();
      assertThat(result.state().availablePermits()).isZero(); // No debt created
    }

    @Test
    @DisplayName("Should enforce a debt floor and reject reservations that would cause excessive debt")
    void should_enforce_a_debt_floor_and_reject_reservations_that_would_cause_excessive_debt() {
      // Given - Capacity is 5, debt floor is -5
      RateLimiterConfig<TokenBucketState> config = defaultConfig();
      TokenBucketState state = new TokenBucketState(-3, NOW, 0L);

      // When - Requesting 3 permits would drop debt to -6, exceeding floor
      ReservationResult<TokenBucketState> result = strategy.reservePermissions(
          state, config, NOW, 3, Duration.ofDays(1)); // Huge timeout

      // Then
      assertThat(result.timedOut()).isTrue();
      assertThat(result.state().availablePermits()).isEqualTo(-3); // State unchanged
    }
  }

  // ================================================================
  // Drain, Reset & Refund
  // ================================================================

  @Nested
  @DisplayName("Drain, Reset and Refund")
  class DrainResetRefund {

    @Test
    @DisplayName("Should drain the bucket by setting available permits to zero without changing the epoch")
    void should_drain_the_bucket_by_setting_available_permits_to_zero_without_changing_the_epoch() {
      // Given
      RateLimiterConfig<TokenBucketState> config = defaultConfig();
      TokenBucketState state = strategy.initial(config, NOW);

      // When
      TokenBucketState drained = strategy.drain(state, config, NOW);

      // Then — permits are zeroed but epoch is unchanged (existing reservations are honored)
      assertThat(drained.availablePermits()).isZero();
      assertThat(drained.epoch()).isEqualTo(state.epoch());
    }

    @Test
    @DisplayName("Should reset the bucket to full capacity and increment the epoch")
    void should_reset_the_bucket_to_full_capacity_and_increment_the_epoch() {
      // Given
      RateLimiterConfig<TokenBucketState> config = defaultConfig();
      TokenBucketState state = new TokenBucketState(-2, NOW, 0L); // Has debt

      // When
      TokenBucketState reset = strategy.reset(state, config, NOW);

      // Then
      assertThat(reset.availablePermits()).isEqualTo(5);
      assertThat(reset.epoch()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should correctly refund permits up to the maximum capacity")
    void should_correctly_refund_permits_up_to_the_maximum_capacity() {
      // Given
      RateLimiterConfig<TokenBucketState> config = defaultConfig();
      TokenBucketState state = new TokenBucketState(2, NOW, 0L);

      // When
      TokenBucketState refunded = strategy.refund(state, config, 2);
      TokenBucketState overRefunded = strategy.refund(state, config, 10);

      // Then
      assertThat(refunded.availablePermits()).isEqualTo(4);
      assertThat(overRefunded.availablePermits()).isEqualTo(5); // Capped at capacity
    }
  }
}
