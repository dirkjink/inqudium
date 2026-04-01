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

class TokenBucketShapingStrategyTest {

  private TokenBucketShapingStrategy strategy;
  private TrafficShaperConfig<TokenBucketShapingState> config;
  private Instant now;

  @BeforeEach
  void setUp() {
    // Burst capacity of 3
    strategy = new TokenBucketShapingStrategy(3);
    // 10 requests per second = 100ms interval
    config = TrafficShaperConfig.<TokenBucketShapingState>builder("token-bucket-shaping-test")
        .withStrategy(strategy)
        .ratePerSecond(10.0)
        .maxQueueDepth(5)
        .throttleMode(ThrottleMode.SHAPE_AND_REJECT_OVERFLOW)
        .build();
    now = Instant.parse("2026-04-02T10:00:00Z");
  }

  @Nested
  @DisplayName("Burst Capacity and Immediate Admission")
  class BurstTests {

    @Test
    @DisplayName("The initial state should allow bursts up to the configured capacity")
    void initialStateShouldAllowBursts() {
      // Given
      TokenBucketShapingState state = strategy.initial(config, now);

      // When: 3 requests arrive at the exact same time (burst)
      ThrottlePermission<TokenBucketShapingState> p1 = strategy.schedule(state, config, now);
      ThrottlePermission<TokenBucketShapingState> p2 = strategy.schedule(p1.state(), config, now);
      ThrottlePermission<TokenBucketShapingState> p3 = strategy.schedule(p2.state(), config, now);

      // Then: All 3 should be admitted immediately
      assertThat(p1.admitted()).isTrue();
      assertThat(p1.requiresWait()).isFalse();

      assertThat(p2.admitted()).isTrue();
      assertThat(p2.requiresWait()).isFalse();

      assertThat(p3.admitted()).isTrue();
      assertThat(p3.requiresWait()).isFalse();
      assertThat(p3.state().availableTokens()).isLessThan(1.0);
    }

    @Test
    @DisplayName("Requests exceeding the burst capacity should be delayed")
    void requestsExceedingBurstCapacityShouldBeDelayed() {
      // Given: Bucket is empty after 3 rapid requests
      TokenBucketShapingState state = strategy.initial(config, now);
      TokenBucketShapingState emptyBucketState = strategy.schedule(
          strategy.schedule(
              strategy.schedule(state, config, now).state(),
              config, now).state(),
          config, now).state();

      // When: A 4th request arrives immediately
      ThrottlePermission<TokenBucketShapingState> p4 = strategy.schedule(emptyBucketState, config, now);

      // Then: It must wait for the next token to refill (100ms)
      assertThat(p4.admitted()).isTrue();
      assertThat(p4.requiresWait()).isTrue();
      assertThat(p4.waitDuration()).isEqualTo(Duration.ofMillis(100));
    }
  }

  @Nested
  @DisplayName("Token Refill Logic")
  class RefillTests {

    @Test
    @DisplayName("Tokens should refill correctly over time allowing new immediate requests")
    void tokensShouldRefillOverTime() {
      // Given: Bucket is empty
      TokenBucketShapingState state = strategy.initial(config, now);
      TokenBucketShapingState emptyBucketState = strategy.schedule(
          strategy.schedule(
              strategy.schedule(state, config, now).state(),
              config, now).state(),
          config, now).state();

      // When: Time advances by 200ms (enough for 2 new tokens)
      Instant futureTime = now.plusMillis(200);
      ThrottlePermission<TokenBucketShapingState> permission = strategy.schedule(emptyBucketState, config, futureTime);

      // Then: Request is admitted immediately because tokens have refilled
      assertThat(permission.admitted()).isTrue();
      assertThat(permission.requiresWait()).isFalse();
      // Should have approx 1 token left (started with ~0, gained 2, consumed 1)
      assertThat(permission.state().availableTokens()).isGreaterThanOrEqualTo(1.0);
    }
  }
}
