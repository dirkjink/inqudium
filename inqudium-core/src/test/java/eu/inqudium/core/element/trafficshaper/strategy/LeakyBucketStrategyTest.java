package eu.inqudium.core.element.trafficshaper.strategy;

import eu.inqudium.core.element.trafficshaper.ThrottleMode;
import eu.inqudium.core.element.trafficshaper.ThrottlePermission;
import eu.inqudium.core.element.trafficshaper.TrafficShaperConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LeakyBucketStrategyTest {

  private LeakyBucketStrategy strategy;
  private TrafficShaperConfig<LeakyBucketState> config;
  private Instant now;

  @BeforeEach
  void setUp() {
    strategy = new LeakyBucketStrategy();
    // 10 requests per second = 100ms interval
    config = TrafficShaperConfig.builder("leaky-test")
        .ratePerSecond(10.0)
        .maxQueueDepth(5)
        .throttleMode(ThrottleMode.SHAPE_AND_REJECT_OVERFLOW)
        .build();
    now = Instant.parse("2026-04-02T10:00:00Z");
  }

  @Nested
  @DisplayName("Initial State and Immediate Admission")
  class InitialStateTests {

    @Test
    @DisplayName("The initial state should allow immediate execution for the first request")
    void initialStateShouldAllowImmediateExecution() {
      // Given
      LeakyBucketState state = strategy.initial(config, now);

      // When
      ThrottlePermission<LeakyBucketState> permission = strategy.schedule(state, config, now);

      // Then
      assertThat(permission.admitted()).isTrue();
      assertThat(permission.requiresWait()).isFalse();
      assertThat(permission.scheduledSlot()).isEqualTo(now);

      // The next free slot should advance by the interval (100ms)
      assertThat(permission.state().nextFreeSlot()).isEqualTo(now.plusMillis(100));
      assertThat(permission.state().queueDepth()).isZero();
    }
  }

  @Nested
  @DisplayName("Delayed Scheduling and Queuing")
  class DelayedSchedulingTests {

    @Test
    @DisplayName("Requests arriving before the next free slot should be delayed")
    void requestsArrivingEarlyShouldBeDelayed() {
      // Given
      LeakyBucketState state = strategy.initial(config, now);
      LeakyBucketState stateAfterOneRequest = strategy.schedule(state, config, now).state();

      // When
      // Arriving at the exact same time (burst)
      ThrottlePermission<LeakyBucketState> permission = strategy.schedule(stateAfterOneRequest, config, now);

      // Then
      assertThat(permission.admitted()).isTrue();
      assertThat(permission.requiresWait()).isTrue();
      assertThat(permission.waitDuration()).isEqualTo(Duration.ofMillis(100));
      assertThat(permission.scheduledSlot()).isEqualTo(now.plusMillis(100));

      // Queue depth increases for delayed requests
      assertThat(permission.state().queueDepth()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Overflow and Rejection")
  class OverflowTests {

    @Test
    @DisplayName("Requests should be rejected when the queue depth limit is reached")
    void requestsShouldBeRejectedWhenQueueIsFull() {
      // Given: Advance state so 5 items are already in the queue
      LeakyBucketState state = new LeakyBucketState(now.plusMillis(500), 5, 5, 0, 0L);

      // When
      ThrottlePermission<LeakyBucketState> permission = strategy.schedule(state, config, now);

      // Then
      assertThat(permission.admitted()).isFalse();
      assertThat(permission.scheduledSlot()).isNull();
      assertThat(permission.state().totalRejected()).isEqualTo(1);
      // Queue depth should remain unchanged
      assertThat(permission.state().queueDepth()).isEqualTo(5);
    }
  }

  @Nested
  @DisplayName("Slot Reclamation")
  class SlotReclamationTests {

    @Test
    @DisplayName("Idle periods should not accumulate burst credits")
    void idlePeriodsShouldNotAccumulateCredits() {
      // Given
      LeakyBucketState state = strategy.initial(config, now);
      Instant tenSecondsLater = now.plusSeconds(10);

      // When
      ThrottlePermission<LeakyBucketState> permission = strategy.schedule(state, config, tenSecondsLater);

      // Then
      // The slot should be reclaimed to 'tenSecondsLater', and not stay at 'now'
      assertThat(permission.admitted()).isTrue();
      assertThat(permission.requiresWait()).isFalse();
      assertThat(permission.state().nextFreeSlot()).isEqualTo(tenSecondsLater.plusMillis(100));
    }
  }
}
