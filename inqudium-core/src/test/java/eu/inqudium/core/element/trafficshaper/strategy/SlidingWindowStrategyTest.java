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

class SlidingWindowStrategyTest {

  private SlidingWindowStrategy strategy;
  private TrafficShaperConfig<SlidingWindowState> config;
  private Instant now;

  @BeforeEach
  void setUp() {
    // Window of 5 seconds, max 10 requests allowed
    strategy = new SlidingWindowStrategy(5, 10);
    config = TrafficShaperConfig.<SlidingWindowState>builder("sliding-window-test")
        .withStrategy(strategy)
        .maxQueueDepth(50)
        .throttleMode(ThrottleMode.SHAPE_AND_REJECT_OVERFLOW)
        .build();
    now = Instant.parse("2026-04-02T10:00:00Z");
  }

  @Nested
  @DisplayName("Window Limits and Queuing")
  class WindowLimitsTests {

    @Test
    @DisplayName("Requests within the sliding window limit should be admitted immediately")
    void requestsWithinLimitShouldBeImmediate() {
      // Given
      SlidingWindowState state = strategy.initial(config, now);

      // When
      ThrottlePermission<SlidingWindowState> permission = strategy.schedule(state, config, now);

      // Then
      assertThat(permission.admitted()).isTrue();
      assertThat(permission.requiresWait()).isFalse();
      assertThat(permission.state().totalInWindow()).isEqualTo(1);
    }

    @Test
    @DisplayName("Requests exceeding the sliding window limit should be delayed until the next second")
    void requestsExceedingLimitShouldBeDelayed() {
      // Given: Window is artificially filled with 10 requests
      SlidingWindowState state = strategy.initial(config, now);
      for (int i = 0; i < 10; i++) {
        state = strategy.schedule(state, config, now).state();
      }

      // When: The 11th request arrives at the same second
      ThrottlePermission<SlidingWindowState> permission = strategy.schedule(state, config, now);

      // Then: It is delayed by exactly 1 second (until the oldest bucket falls out or new bucket starts)
      assertThat(permission.admitted()).isTrue();
      assertThat(permission.requiresWait()).isTrue();
      assertThat(permission.waitDuration()).isEqualTo(Duration.ofSeconds(1));
      assertThat(permission.scheduledSlot()).isEqualTo(now.plusSeconds(1));
    }
  }

  @Nested
  @DisplayName("Window Sliding and Expiration")
  class WindowSlidingTests {

    @Test
    @DisplayName("Expired buckets should free up capacity within the window")
    void expiredBucketsShouldFreeUpCapacity() {
      // Given: Fill the window at the current second
      SlidingWindowState state = strategy.initial(config, now);
      for (int i = 0; i < 10; i++) {
        state = strategy.schedule(state, config, now).state();
      }

      // When: 6 seconds pass (the 5-second window completely slides past the initial requests)
      Instant futureTime = now.plusSeconds(6);
      ThrottlePermission<SlidingWindowState> permission = strategy.schedule(state, config, futureTime);

      // Then: The old requests are out of the window, so this one proceeds immediately
      assertThat(permission.admitted()).isTrue();
      assertThat(permission.requiresWait()).isFalse();
      // The total in the new window should just be this 1 new request
      assertThat(permission.state().totalInWindow()).isEqualTo(1);
    }
  }
}
