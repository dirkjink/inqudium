package eu.inqudium.core.ratelimiter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    // Initial State
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

    // ================================================================
    // Refill Logic
    // ================================================================

    @Nested
    @DisplayName("Token Refill Logic")
    class TokenRefillLogic {

        @Test
        @DisplayName("should not refill when no time has elapsed")
        void should_not_refill_when_no_time_has_elapsed() {
            // Given
            RateLimiterConfig config = defaultConfig();
            RateLimiterSnapshot snapshot = new RateLimiterSnapshot(0, NOW);

            // When
            RateLimiterSnapshot refilled = RateLimiterCore.refill(snapshot, config, NOW);

            // Then
            assertThat(refilled.availablePermits()).isZero();
        }

        @Test
        @DisplayName("should refill permits after one full period has elapsed")
        void should_refill_permits_after_one_full_period_has_elapsed() {
            // Given
            RateLimiterConfig config = defaultConfig(); // 5 permits per 1s
            RateLimiterSnapshot snapshot = new RateLimiterSnapshot(0, NOW);

            // When
            RateLimiterSnapshot refilled = RateLimiterCore.refill(snapshot, config, NOW.plusSeconds(1));

            // Then
            assertThat(refilled.availablePermits()).isEqualTo(5);
        }

        @Test
        @DisplayName("should not refill before a full period has completed")
        void should_not_refill_before_a_full_period_has_completed() {
            // Given
            RateLimiterConfig config = defaultConfig(); // 5 permits per 1s
            RateLimiterSnapshot snapshot = new RateLimiterSnapshot(0, NOW);

            // When — only 500ms elapsed
            RateLimiterSnapshot refilled = RateLimiterCore.refill(snapshot, config, NOW.plusMillis(500));

            // Then
            assertThat(refilled.availablePermits()).isZero();
        }

        @Test
        @DisplayName("should refill multiple periods worth of permits when several periods have elapsed")
        void should_refill_multiple_periods_worth_of_permits_when_several_periods_have_elapsed() {
            // Given — capacity=5, refill=2 per 1s, starting at 0
            RateLimiterConfig config = RateLimiterConfig.builder("multi-refill")
                    .capacity(10)
                    .refillPermits(2)
                    .refillPeriod(Duration.ofSeconds(1))
                    .build();
            RateLimiterSnapshot snapshot = new RateLimiterSnapshot(0, NOW);

            // When — 3 seconds elapsed → 3 periods × 2 permits = 6
            RateLimiterSnapshot refilled = RateLimiterCore.refill(snapshot, config, NOW.plusSeconds(3));

            // Then
            assertThat(refilled.availablePermits()).isEqualTo(6);
        }

        @Test
        @DisplayName("should cap the refill at the configured capacity")
        void should_cap_the_refill_at_the_configured_capacity() {
            // Given
            RateLimiterConfig config = defaultConfig(); // capacity=5, refill=5 per 1s
            RateLimiterSnapshot snapshot = new RateLimiterSnapshot(3, NOW);

            // When — refill 5, but 3+5=8 exceeds capacity 5
            RateLimiterSnapshot refilled = RateLimiterCore.refill(snapshot, config, NOW.plusSeconds(1));

            // Then
            assertThat(refilled.availablePermits()).isEqualTo(5);
        }

        @Test
        @DisplayName("should preserve the fractional remainder of elapsed time for the next refill")
        void should_preserve_the_fractional_remainder_of_elapsed_time_for_the_next_refill() {
            // Given
            RateLimiterConfig config = defaultConfig(); // 1s period
            RateLimiterSnapshot snapshot = new RateLimiterSnapshot(0, NOW);

            // When — 1.7s elapsed → 1 full period consumed
            RateLimiterSnapshot refilled = RateLimiterCore.refill(snapshot, config, NOW.plusMillis(1700));

            // Then — refill time advanced by exactly 1s (not 1.7s)
            assertThat(refilled.lastRefillTime()).isEqualTo(NOW.plusSeconds(1));
        }

        @Test
        @DisplayName("should not change the snapshot when time goes backwards")
        void should_not_change_the_snapshot_when_time_goes_backwards() {
            // Given
            RateLimiterConfig config = defaultConfig();
            RateLimiterSnapshot snapshot = new RateLimiterSnapshot(3, NOW);

            // When
            RateLimiterSnapshot refilled = RateLimiterCore.refill(snapshot, config, NOW.minusSeconds(5));

            // Then
            assertThat(refilled).isEqualTo(snapshot);
        }
    }

    // ================================================================
    // Permission — Fail-Fast Single Permit
    // ================================================================

    @Nested
    @DisplayName("Permission Acquisition — Single Permit (Fail-Fast)")
    class SinglePermitFailFast {

        @Test
        @DisplayName("should permit when tokens are available and consume one token")
        void should_permit_when_tokens_are_available_and_consume_one_token() {
            // Given
            RateLimiterConfig config = defaultConfig();
            RateLimiterSnapshot snapshot = RateLimiterSnapshot.initial(config, NOW);

            // When
            RateLimitPermission result = RateLimiterCore.tryAcquirePermission(snapshot, config, NOW);

            // Then
            assertThat(result.permitted()).isTrue();
            assertThat(result.snapshot().availablePermits()).isEqualTo(4);
        }

        @Test
        @DisplayName("should reject when the bucket is empty")
        void should_reject_when_the_bucket_is_empty() {
            // Given
            RateLimiterConfig config = defaultConfig();
            RateLimiterSnapshot snapshot = new RateLimiterSnapshot(0, NOW);

            // When
            RateLimitPermission result = RateLimiterCore.tryAcquirePermission(snapshot, config, NOW);

            // Then
            assertThat(result.permitted()).isFalse();
            assertThat(result.waitDuration()).isPositive();
        }

        @Test
        @DisplayName("should consume all permits one by one until the bucket is empty")
        void should_consume_all_permits_one_by_one_until_the_bucket_is_empty() {
            // Given
            RateLimiterConfig config = defaultConfig(); // capacity=5
            RateLimiterSnapshot snapshot = RateLimiterSnapshot.initial(config, NOW);

            // When — consume 5 permits
            RateLimiterSnapshot current = snapshot;
            for (int i = 0; i < 5; i++) {
                RateLimitPermission perm = RateLimiterCore.tryAcquirePermission(current, config, NOW);
                assertThat(perm.permitted()).isTrue();
                current = perm.snapshot();
            }

            // Then — 6th should be rejected
            RateLimitPermission rejected = RateLimiterCore.tryAcquirePermission(current, config, NOW);
            assertThat(rejected.permitted()).isFalse();
            assertThat(current.availablePermits()).isZero();
        }

        @Test
        @DisplayName("should refill and then permit when enough time has elapsed")
        void should_refill_and_then_permit_when_enough_time_has_elapsed() {
            // Given — empty bucket
            RateLimiterConfig config = defaultConfig();
            RateLimiterSnapshot snapshot = new RateLimiterSnapshot(0, NOW);

            // When — 1 second later, refill should occur
            RateLimitPermission result = RateLimiterCore.tryAcquirePermission(
                    snapshot, config, NOW.plusSeconds(1));

            // Then
            assertThat(result.permitted()).isTrue();
            assertThat(result.snapshot().availablePermits()).isEqualTo(4); // 5 refilled - 1 consumed
        }

        @Test
        @DisplayName("should report the estimated wait duration when rejected")
        void should_report_the_estimated_wait_duration_when_rejected() {
            // Given
            RateLimiterConfig config = RateLimiterConfig.builder("wait-test")
                    .capacity(5)
                    .refillPermits(5)
                    .refillPeriod(Duration.ofSeconds(2))
                    .build();
            RateLimiterSnapshot snapshot = new RateLimiterSnapshot(0, NOW);

            // When
            RateLimitPermission result = RateLimiterCore.tryAcquirePermission(snapshot, config, NOW);

            // Then — need 1 permit, refill adds 5 per 2s period → wait = 2s
            assertThat(result.waitDuration()).isEqualTo(Duration.ofSeconds(2));
        }
    }

    // ================================================================
    // Permission — Multiple Permits
    // ================================================================

    @Nested
    @DisplayName("Permission Acquisition — Multiple Permits")
    class MultiplePermits {

        @Test
        @DisplayName("should permit when enough tokens are available for a batch request")
        void should_permit_when_enough_tokens_are_available_for_a_batch_request() {
            // Given
            RateLimiterConfig config = defaultConfig(); // capacity=5
            RateLimiterSnapshot snapshot = RateLimiterSnapshot.initial(config, NOW);

            // When
            RateLimitPermission result = RateLimiterCore.tryAcquirePermissions(
                    snapshot, config, NOW, 3);

            // Then
            assertThat(result.permitted()).isTrue();
            assertThat(result.snapshot().availablePermits()).isEqualTo(2);
        }

        @Test
        @DisplayName("should reject when not enough tokens are available for a batch request")
        void should_reject_when_not_enough_tokens_are_available_for_a_batch_request() {
            // Given
            RateLimiterConfig config = defaultConfig(); // capacity=5
            RateLimiterSnapshot snapshot = new RateLimiterSnapshot(2, NOW);

            // When
            RateLimitPermission result = RateLimiterCore.tryAcquirePermissions(
                    snapshot, config, NOW, 4);

            // Then
            assertThat(result.permitted()).isFalse();
            assertThat(result.waitDuration()).isPositive();
        }

        @Test
        @DisplayName("should reject when requesting more permits than capacity")
        void should_reject_when_requesting_more_permits_than_capacity() {
            // Given
            RateLimiterConfig config = defaultConfig(); // capacity=5

            // When / Then
            assertThatThrownBy(() -> RateLimiterCore.tryAcquirePermissions(
                    RateLimiterSnapshot.initial(config, NOW), config, NOW, 6))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds capacity");
        }

        @Test
        @DisplayName("should reject when requesting zero or negative permits")
        void should_reject_when_requesting_zero_or_negative_permits() {
            // Given
            RateLimiterConfig config = defaultConfig();

            // When / Then
            assertThatThrownBy(() -> RateLimiterCore.tryAcquirePermissions(
                    RateLimiterSnapshot.initial(config, NOW), config, NOW, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ================================================================
    // Reservation (wait-capable)
    // ================================================================

    @Nested
    @DisplayName("Permit Reservation")
    class PermitReservation {

        @Test
        @DisplayName("should return an immediate reservation when permits are available")
        void should_return_an_immediate_reservation_when_permits_are_available() {
            // Given
            RateLimiterConfig config = defaultConfig();
            RateLimiterSnapshot snapshot = RateLimiterSnapshot.initial(config, NOW);

            // When
            ReservationResult result = RateLimiterCore.reservePermission(
                    snapshot, config, NOW, Duration.ofSeconds(5));

            // Then
            assertThat(result.timedOut()).isFalse();
            assertThat(result.waitDuration()).isEqualTo(Duration.ZERO);
            assertThat(result.snapshot().availablePermits()).isEqualTo(4);
        }

        @Test
        @DisplayName("should return a delayed reservation when the bucket is empty but timeout allows waiting")
        void should_return_a_delayed_reservation_when_the_bucket_is_empty_but_timeout_allows_waiting() {
            // Given — empty bucket, timeout = 5s, refill = 5 per 1s
            RateLimiterConfig config = defaultConfig();
            RateLimiterSnapshot snapshot = new RateLimiterSnapshot(0, NOW);

            // When
            ReservationResult result = RateLimiterCore.reservePermission(
                    snapshot, config, NOW, Duration.ofSeconds(5));

            // Then — wait = 1s (one full period), permit consumed (goes to -1 conceptually)
            assertThat(result.timedOut()).isFalse();
            assertThat(result.waitDuration()).isEqualTo(Duration.ofSeconds(1));
            assertThat(result.snapshot().availablePermits()).isEqualTo(-1);
        }

        @Test
        @DisplayName("should time out when the estimated wait exceeds the timeout")
        void should_time_out_when_the_estimated_wait_exceeds_the_timeout() {
            // Given — empty bucket, timeout = 100ms, refill period = 1s
            RateLimiterConfig config = defaultConfig();
            RateLimiterSnapshot snapshot = new RateLimiterSnapshot(0, NOW);

            // When
            ReservationResult result = RateLimiterCore.reservePermission(
                    snapshot, config, NOW, Duration.ofMillis(100));

            // Then
            assertThat(result.timedOut()).isTrue();
            assertThat(result.snapshot().availablePermits()).isZero(); // permit NOT consumed
        }

        @Test
        @DisplayName("should time out immediately when the timeout is zero and bucket is empty")
        void should_time_out_immediately_when_the_timeout_is_zero_and_bucket_is_empty() {
            // Given
            RateLimiterConfig config = defaultConfig();
            RateLimiterSnapshot snapshot = new RateLimiterSnapshot(0, NOW);

            // When
            ReservationResult result = RateLimiterCore.reservePermission(
                    snapshot, config, NOW, Duration.ZERO);

            // Then
            assertThat(result.timedOut()).isTrue();
        }
    }

    // ================================================================
    // Drain & Reset
    // ================================================================

    @Nested
    @DisplayName("Drain and Reset")
    class DrainAndReset {

        @Test
        @DisplayName("should drain all permits from the bucket")
        void should_drain_all_permits_from_the_bucket() {
            // Given
            RateLimiterConfig config = defaultConfig();
            RateLimiterSnapshot snapshot = RateLimiterSnapshot.initial(config, NOW);

            // When
            RateLimiterSnapshot drained = RateLimiterCore.drain(snapshot);

            // Then
            assertThat(drained.availablePermits()).isZero();
        }

        @Test
        @DisplayName("should preserve the last refill time when draining")
        void should_preserve_the_last_refill_time_when_draining() {
            // Given
            RateLimiterConfig config = defaultConfig();
            RateLimiterSnapshot snapshot = RateLimiterSnapshot.initial(config, NOW);

            // When
            RateLimiterSnapshot drained = RateLimiterCore.drain(snapshot);

            // Then
            assertThat(drained.lastRefillTime()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("should reset the bucket to full capacity with a new refill time")
        void should_reset_the_bucket_to_full_capacity_with_a_new_refill_time() {
            // Given
            RateLimiterConfig config = defaultConfig();
            Instant later = NOW.plusSeconds(100);

            // When
            RateLimiterSnapshot fresh = RateLimiterCore.reset(config, later);

            // Then
            assertThat(fresh.availablePermits()).isEqualTo(config.capacity());
            assertThat(fresh.lastRefillTime()).isEqualTo(later);
        }
    }

    // ================================================================
    // Wait Duration Estimation
    // ================================================================

    @Nested
    @DisplayName("Wait Duration Estimation")
    class WaitDurationEstimation {

        @Test
        @DisplayName("should estimate zero wait when permits are available")
        void should_estimate_zero_wait_when_permits_are_available() {
            // Given
            RateLimiterConfig config = defaultConfig();
            RateLimiterSnapshot snapshot = RateLimiterSnapshot.initial(config, NOW);

            // When
            Duration wait = RateLimiterCore.estimateWaitDuration(snapshot, config, NOW);

            // Then
            assertThat(wait).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("should estimate one refill period when the bucket is empty")
        void should_estimate_one_refill_period_when_the_bucket_is_empty() {
            // Given
            RateLimiterConfig config = defaultConfig(); // 1s period
            RateLimiterSnapshot snapshot = new RateLimiterSnapshot(0, NOW);

            // When
            Duration wait = RateLimiterCore.estimateWaitDuration(snapshot, config, NOW);

            // Then
            assertThat(wait).isEqualTo(Duration.ofSeconds(1));
        }

        @Test
        @DisplayName("should correctly estimate wait when multiple permits are needed over several periods")
        void should_correctly_estimate_wait_when_multiple_permits_are_needed_over_several_periods() {
            // Given — refill 2 per 500ms, need 5 permits
            RateLimiterConfig config = RateLimiterConfig.builder("multi-wait")
                    .capacity(10)
                    .refillPermits(2)
                    .refillPeriod(Duration.ofMillis(500))
                    .build();

            // When — need 5 permits, get 2 per period → ceil(5/2)=3 periods → 1500ms
            Duration wait = RateLimiterCore.estimateWaitForPermits(config, 5);

            // Then
            assertThat(wait).isEqualTo(Duration.ofMillis(1500));
        }
    }

    // ================================================================
    // Snapshot Immutability
    // ================================================================

    @Nested
    @DisplayName("Snapshot Immutability")
    class SnapshotImmutability {

        @Test
        @DisplayName("should not modify the original snapshot when acquiring a permit")
        void should_not_modify_the_original_snapshot_when_acquiring_a_permit() {
            // Given
            RateLimiterConfig config = defaultConfig();
            RateLimiterSnapshot original = RateLimiterSnapshot.initial(config, NOW);

            // When
            RateLimiterCore.tryAcquirePermission(original, config, NOW);

            // Then
            assertThat(original.availablePermits()).isEqualTo(5);
        }

        @Test
        @DisplayName("should not modify the original snapshot when refilling")
        void should_not_modify_the_original_snapshot_when_refilling() {
            // Given
            RateLimiterConfig config = defaultConfig();
            RateLimiterSnapshot original = new RateLimiterSnapshot(0, NOW);

            // When
            RateLimiterCore.refill(original, config, NOW.plusSeconds(1));

            // Then
            assertThat(original.availablePermits()).isZero();
            assertThat(original.lastRefillTime()).isEqualTo(NOW);
        }
    }

    // ================================================================
    // Full Token Bucket Lifecycle
    // ================================================================

    @Nested
    @DisplayName("Full Token Bucket Lifecycle")
    class FullLifecycle {

        @Test
        @DisplayName("should deplete the bucket and then recover after a refill period")
        void should_deplete_the_bucket_and_then_recover_after_a_refill_period() {
            // Given
            RateLimiterConfig config = RateLimiterConfig.builder("lifecycle")
                    .capacity(3)
                    .refillPermits(3)
                    .refillPeriod(Duration.ofSeconds(2))
                    .build();
            RateLimiterSnapshot snapshot = RateLimiterSnapshot.initial(config, NOW);

            // When — consume all 3 permits
            RateLimiterSnapshot current = snapshot;
            for (int i = 0; i < 3; i++) {
                RateLimitPermission perm = RateLimiterCore.tryAcquirePermission(current, config, NOW);
                assertThat(perm.permitted()).isTrue();
                current = perm.snapshot();
            }

            // Then — 4th should be rejected
            RateLimitPermission rejected = RateLimiterCore.tryAcquirePermission(current, config, NOW);
            assertThat(rejected.permitted()).isFalse();

            // When — wait 2 seconds for refill
            RateLimitPermission afterRefill = RateLimiterCore.tryAcquirePermission(
                    current, config, NOW.plusSeconds(2));

            // Then — should be permitted again
            assertThat(afterRefill.permitted()).isTrue();
            assertThat(afterRefill.snapshot().availablePermits()).isEqualTo(2); // 3 refilled - 1 consumed
        }

        @Test
        @DisplayName("should handle a sustained request rate at steady state")
        void should_handle_a_sustained_request_rate_at_steady_state() {
            // Given — 10 permits per second
            RateLimiterConfig config = RateLimiterConfig.builder("steady-state")
                    .capacity(10)
                    .refillPermits(10)
                    .refillPeriod(Duration.ofSeconds(1))
                    .build();
            RateLimiterSnapshot snapshot = RateLimiterSnapshot.initial(config, NOW);

            // When — simulate 10 requests per second over 3 seconds
            RateLimiterSnapshot current = snapshot;
            int totalPermitted = 0;
            for (int second = 0; second < 3; second++) {
                Instant time = NOW.plusSeconds(second);
                for (int req = 0; req < 10; req++) {
                    RateLimitPermission perm = RateLimiterCore.tryAcquirePermission(current, config, time);
                    if (perm.permitted()) {
                        totalPermitted++;
                    }
                    current = perm.snapshot();
                }
            }

            // Then — all 30 requests should be permitted (10 per second)
            assertThat(totalPermitted).isEqualTo(30);
        }
    }

    // ================================================================
    // Configuration Validation
    // ================================================================

    @Nested
    @DisplayName("Configuration Validation")
    class ConfigurationValidation {

        @Test
        @DisplayName("should reject a capacity of zero")
        void should_reject_a_capacity_of_zero() {
            assertThatThrownBy(() -> RateLimiterConfig.builder("bad")
                    .capacity(0)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject a zero refill period")
        void should_reject_a_zero_refill_period() {
            assertThatThrownBy(() -> RateLimiterConfig.builder("bad")
                    .refillPeriod(Duration.ZERO)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject a negative default timeout")
        void should_reject_a_negative_default_timeout() {
            assertThatThrownBy(() -> RateLimiterConfig.builder("bad")
                    .defaultTimeout(Duration.ofSeconds(-1))
                    .build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject a null name")
        void should_reject_a_null_name() {
            assertThatThrownBy(() -> RateLimiterConfig.builder(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should build a valid config with the limit for period convenience method")
        void should_build_a_valid_config_with_the_limit_for_period_convenience_method() {
            // Given / When
            RateLimiterConfig config = RateLimiterConfig.builder("convenience")
                    .limitForPeriod(100, Duration.ofMinutes(1))
                    .build();

            // Then
            assertThat(config.capacity()).isEqualTo(100);
            assertThat(config.refillPermits()).isEqualTo(100);
            assertThat(config.refillPeriod()).isEqualTo(Duration.ofMinutes(1));
        }
    }

    // ================================================================
    // Nanos-Per-Permit Calculation
    // ================================================================

    @Nested
    @DisplayName("Nanos-Per-Permit Calculation")
    class NanosPerPermit {

        @Test
        @DisplayName("should correctly calculate nanos per permit for a simple rate")
        void should_correctly_calculate_nanos_per_permit_for_a_simple_rate() {
            // Given — 10 permits per 1s = 100ms per permit
            RateLimiterConfig config = RateLimiterConfig.builder("nanos-test")
                    .capacity(10)
                    .refillPermits(10)
                    .refillPeriod(Duration.ofSeconds(1))
                    .build();

            // When
            Duration nanosPerPermit = config.nanosPerPermit();

            // Then
            assertThat(nanosPerPermit).isEqualTo(Duration.ofMillis(100));
        }

        @Test
        @DisplayName("should return at least 1 nano per permit even for very high rates")
        void should_return_at_least_1_nano_per_permit_even_for_very_high_rates() {
            // Given — extreme rate
            RateLimiterConfig config = RateLimiterConfig.builder("extreme")
                    .capacity(1_000_000)
                    .refillPermits(1_000_000)
                    .refillPeriod(Duration.ofNanos(1))
                    .build();

            // When
            Duration nanosPerPermit = config.nanosPerPermit();

            // Then
            assertThat(nanosPerPermit.toNanos()).isGreaterThanOrEqualTo(1);
        }
    }
}
