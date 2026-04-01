package eu.inqudium.core.ratelimiter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RateLimiterCore — Functional Token Bucket State Machine")
class RateLimiterCoreTest {

  private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");

  private static RateLimiterConfig defaultConfig() {
    return RateLimiterConfig.builder("test")
        .capacity(5)
        .refillPermits(5)
        .refillPeriod(Duration.ofSeconds(1))
        .build();
  }

  // ================================================================
  // Initial State & Refill Logic (Unchanged but included for completeness)
  // ================================================================

  @Nested
  @DisplayName("Initial State")
  class InitialState {

    @Test
    @DisplayName("a freshly created snapshot should have a full bucket")
    void a_freshly_created_snapshot_should_have_a_full_bucket() {
      // Given
      RateLimiterConfig config = defaultConfig();

      // When
      RateLimiterSnapshot snapshot = RateLimiterSnapshot.initial(config, NOW);

      // Then
      assertThat(snapshot.availablePermits()).isEqualTo(5);
      assertThat(snapshot.epoch()).isZero();
    }

    @Test
    @DisplayName("a freshly created snapshot should record the creation timestamp as last refill time")
    void a_freshly_created_snapshot_should_record_the_creation_timestamp_as_last_refill_time() {
      // Given
      RateLimiterConfig config = defaultConfig();

      // When
      RateLimiterSnapshot snapshot = RateLimiterSnapshot.initial(config, NOW);

      // Then
      assertThat(snapshot.lastRefillTime()).isEqualTo(NOW);
    }
  }

  @Nested
  @DisplayName("Token Refill Logic")
  class TokenRefillLogic {

    @Test
    @DisplayName("should not refill when no time has elapsed")
    void should_not_refill_when_no_time_has_elapsed() {
      // Given
      RateLimiterConfig config = defaultConfig();
      RateLimiterSnapshot snapshot = new RateLimiterSnapshot(0, NOW, 0L);

      // When
      RateLimiterSnapshot refilled = RateLimiterCore.refill(snapshot, config, NOW);

      // Then
      assertThat(refilled.availablePermits()).isZero();
    }

    @Test
    @DisplayName("should cap the refill at the configured capacity")
    void should_cap_the_refill_at_the_configured_capacity() {
      // Given
      RateLimiterConfig config = defaultConfig(); // capacity=5, refill=5 per 1s
      RateLimiterSnapshot snapshot = new RateLimiterSnapshot(3, NOW, 0L);

      // When — refill 5, but 3+5=8 exceeds capacity 5
      RateLimiterSnapshot refilled = RateLimiterCore.refill(snapshot, config, NOW.plusSeconds(1));

      // Then
      assertThat(refilled.availablePermits()).isEqualTo(5);
    }
  }

  // ================================================================
  // Reservation & Multi-Permit
  // ================================================================

  @Nested
  @DisplayName("Permit Reservation (Multi-Permit Support)")
  class PermitReservation {

    @Test
    @DisplayName("should reserve multiple permits immediately when enough tokens are available")
    void should_reserve_multiple_permits_immediately_when_enough_tokens_are_available() {
      // Given
      RateLimiterConfig config = defaultConfig();
      RateLimiterSnapshot snapshot = RateLimiterSnapshot.initial(config, NOW);

      // When
      ReservationResult result = RateLimiterCore.reservePermissions(
          snapshot, config, NOW, 3, Duration.ofSeconds(5));

      // Then
      assertThat(result.timedOut()).isFalse();
      assertThat(result.waitDuration()).isEqualTo(Duration.ZERO);
      assertThat(result.snapshot().availablePermits()).isEqualTo(2); // 5 - 3
    }

    @Test
    @DisplayName("should return a delayed reservation for multiple permits when the bucket is empty but timeout allows waiting")
    void should_return_a_delayed_reservation_for_multiple_permits_when_the_bucket_is_empty() {
      // Given — empty bucket, timeout = 5s, refill = 5 per 1s
      RateLimiterConfig config = defaultConfig();
      RateLimiterSnapshot snapshot = new RateLimiterSnapshot(0, NOW, 0L);

      // When - ask for 2 permits
      ReservationResult result = RateLimiterCore.reservePermissions(
          snapshot, config, NOW, 2, Duration.ofSeconds(5));

      // Then — wait = 1s (one full period to get 5 permits), permits consumed (goes to -2)
      assertThat(result.timedOut()).isFalse();
      assertThat(result.waitDuration()).isEqualTo(Duration.ofSeconds(1));
      assertThat(result.snapshot().availablePermits()).isEqualTo(-2);
    }

    @Test
    @DisplayName("should time out when the estimated wait for multiple permits exceeds the timeout")
    void should_time_out_when_the_estimated_wait_for_multiple_permits_exceeds_the_timeout() {
      // Given — empty bucket, timeout = 100ms, refill period = 1s
      RateLimiterConfig config = defaultConfig();
      RateLimiterSnapshot snapshot = new RateLimiterSnapshot(0, NOW, 0L);

      // When - asking for 5 permits takes 1s to refill
      ReservationResult result = RateLimiterCore.reservePermissions(
          snapshot, config, NOW, 5, Duration.ofMillis(100));

      // Then
      assertThat(result.timedOut()).isTrue();
      assertThat(result.snapshot().availablePermits()).isZero(); // permit NOT consumed
    }
  }

  // ================================================================
  // Drain, Reset & Refund
  // ================================================================

  @Nested
  @DisplayName("Drain, Reset and Refund")
  class DrainResetAndRefund {

    @Test
    @DisplayName("should drain all permits from the bucket and increment epoch")
    void should_drain_all_permits_from_the_bucket_and_increment_epoch() {
      // Given
      RateLimiterConfig config = defaultConfig();
      RateLimiterSnapshot snapshot = RateLimiterSnapshot.initial(config, NOW);

      // When
      RateLimiterSnapshot drained = RateLimiterCore.drain(snapshot, NOW);

      // Then
      assertThat(drained.availablePermits()).isZero();
      assertThat(drained.epoch()).isEqualTo(1L); // Fix 3: Epoch incremented on drain
    }

    @Test
    @DisplayName("should reset the bucket to full capacity with a new refill time and incremented epoch")
    void should_reset_the_bucket_to_full_capacity_with_a_new_refill_time_and_incremented_epoch() {
      // Given
      RateLimiterConfig config = defaultConfig();
      Instant later = NOW.plusSeconds(100);

      // When
      RateLimiterSnapshot fresh = RateLimiterCore.reset(RateLimiterSnapshot.initial(config, NOW), config, later);

      // Then
      assertThat(fresh.availablePermits()).isEqualTo(config.capacity());
      assertThat(fresh.lastRefillTime()).isEqualTo(later);
      assertThat(fresh.epoch()).isEqualTo(1L); // Fix 2/7
    }

    @Test
    @DisplayName("should correctly refund multiple permits up to the maximum capacity")
    void should_correctly_refund_multiple_permits_up_to_the_maximum_capacity() {
      // Given
      RateLimiterConfig config = defaultConfig(); // capacity 5
      RateLimiterSnapshot snapshot = new RateLimiterSnapshot(1, NOW, 0L);

      // When - refunding 2 permits
      RateLimiterSnapshot refunded = RateLimiterCore.refund(snapshot, config, 2);

      // Then
      assertThat(refunded.availablePermits()).isEqualTo(3);
    }

    @Test
    @DisplayName("should not exceed capacity when refunding permits")
    void should_not_exceed_capacity_when_refunding_permits() {
      // Given
      RateLimiterConfig config = defaultConfig(); // capacity 5
      RateLimiterSnapshot snapshot = new RateLimiterSnapshot(4, NOW, 0L);

      // When - refunding 5 permits, should cap at 5
      RateLimiterSnapshot refunded = RateLimiterCore.refund(snapshot, config, 5);

      // Then
      assertThat(refunded.availablePermits()).isEqualTo(5);
    }
  }

  // ================================================================
  // Wait Duration Estimation
  // ================================================================

  @Nested
  @DisplayName("Wait Duration Estimation")
  class WaitDurationEstimation {

    @Test
    @DisplayName("should estimate zero wait when required permits are available")
    void should_estimate_zero_wait_when_required_permits_are_available() {
      // Given
      RateLimiterConfig config = defaultConfig();
      RateLimiterSnapshot snapshot = RateLimiterSnapshot.initial(config, NOW);

      // When
      Duration wait = RateLimiterCore.estimateWaitDuration(snapshot, config, NOW, 3);

      // Then
      assertThat(wait).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("should estimate correct wait time when bucket is empty")
    void should_estimate_correct_wait_time_when_bucket_is_empty() {
      // Given
      RateLimiterConfig config = defaultConfig(); // 5 permits per 1s
      RateLimiterSnapshot snapshot = new RateLimiterSnapshot(0, NOW, 0L);

      // When - asking for 1 permit
      Duration wait = RateLimiterCore.estimateWaitDuration(snapshot, config, NOW, 1);

      // Then
      assertThat(wait).isEqualTo(Duration.ofSeconds(1));
    }
  }
}