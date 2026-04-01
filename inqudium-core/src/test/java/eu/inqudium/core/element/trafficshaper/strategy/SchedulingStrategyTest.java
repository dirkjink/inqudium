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

class SchedulingStrategyTest {

  private TestStrategy strategy;
  private Instant now;

  @BeforeEach
  void setUp() {
    strategy = new TestStrategy();
    now = Instant.parse("2026-04-02T10:00:00Z");
  }

  @Nested
  @DisplayName("Default Feedback Methods")
  class FeedbackMethodsTests {

    @Test
    @DisplayName("The default implementation of recordSuccess should return the state unchanged")
    void recordSuccessShouldReturnUnchangedState() {
      // Given
      TestState state = new TestState(0, 0, 0, 0, Duration.ZERO);

      // When
      TestState result = strategy.recordSuccess(state);

      // Then
      assertThat(result).isSameAs(state);
    }

    @Test
    @DisplayName("The default implementation of recordFailure should return the state unchanged")
    void recordFailureShouldReturnUnchangedState() {
      // Given
      TestState state = new TestState(0, 0, 0, 0, Duration.ZERO);

      // When
      TestState result = strategy.recordFailure(state);

      // Then
      assertThat(result).isSameAs(state);
    }
  }

  @Nested
  @DisplayName("Overflow Rejection Checks")
  class ShouldRejectTests {

    @Test
    @DisplayName("Requests within limits should not be rejected")
    void requestsWithinLimitsShouldNotBeRejected() {
      // Given: Limit is queue depth 10, wait 5s
      TrafficShaperConfig<TestState> config = TrafficShaperConfig.<TestState>builder("test")
          .withStrategy(strategy)
          .maxQueueDepth(10)
          .maxWaitDuration(Duration.ofSeconds(5))
          .build();
      TestState state = new TestState(0, 5, 0, 0, Duration.ZERO); // 5 in queue

      // When
      boolean reject = strategy.shouldReject(state, config, Duration.ofSeconds(2));

      // Then
      assertThat(reject).isFalse();
    }

    @Test
    @DisplayName("Requests exceeding the queue depth limit should be rejected")
    void requestsExceedingQueueDepthShouldBeRejected() {
      // Given: Limit is queue depth 10
      TrafficShaperConfig<TestState> config = TrafficShaperConfig.<TestState>builder("test")
          .withStrategy(strategy)
          .maxQueueDepth(10)
          .build();
      TestState state = new TestState(0, 10, 0, 0, Duration.ZERO); // Queue is full

      // When
      boolean reject = strategy.shouldReject(state, config, Duration.ofSeconds(1));

      // Then
      assertThat(reject).isTrue();
    }

    @Test
    @DisplayName("Requests exceeding the maximum wait duration should be rejected")
    void requestsExceedingWaitDurationShouldBeRejected() {
      // Given: Limit is wait 5s
      TrafficShaperConfig<TestState> config = TrafficShaperConfig.<TestState>builder("test")
          .withStrategy(strategy)
          .maxQueueDepth(100)
          .maxWaitDuration(Duration.ofSeconds(5))
          .build();
      TestState state = new TestState(0, 1, 0, 0, Duration.ZERO);

      // When: Wait duration would be 6 seconds
      boolean reject = strategy.shouldReject(state, config, Duration.ofSeconds(6));

      // Then
      assertThat(reject).isTrue();
    }
  }

  @Nested
  @DisplayName("Unbounded Queue Warnings")
  class UnboundedWarningTests {

    @Test
    @DisplayName("Warning should not trigger if the throttle mode is not unbounded")
    void warningShouldNotTriggerInStandardMode() {
      // Given
      TrafficShaperConfig<TestState> config = TrafficShaperConfig.<TestState>builder("test")
          .withStrategy(strategy)
          .throttleMode(ThrottleMode.SHAPE_AND_REJECT_OVERFLOW)
          .unboundedWarnAfter(Duration.ofSeconds(10))
          .build();

      // Tail wait is huge, but mode doesn't allow unbounded warnings
      TestState state = new TestState(0, 0, 0, 0, Duration.ofDays(1));

      // When
      boolean warn = strategy.checkUnboundedWarning(state, config, now);

      // Then
      assertThat(warn).isFalse();
    }

    @Test
    @DisplayName("Warning should trigger if the projected tail wait exceeds the warning threshold in unbounded mode")
    void warningShouldTriggerWhenThresholdExceeded() {
      // Given
      TrafficShaperConfig<TestState> config = TrafficShaperConfig.<TestState>builder("test")
          .withStrategy(strategy)
          .throttleMode(ThrottleMode.SHAPE_UNBOUNDED)
          .unboundedWarnAfter(Duration.ofSeconds(10))
          .build();

      // Tail wait is 11 seconds (exceeds 10s warning threshold)
      TestState state = new TestState(0, 0, 0, 0, Duration.ofSeconds(11));

      // When
      boolean warn = strategy.checkUnboundedWarning(state, config, now);

      // Then
      assertThat(warn).isTrue();
    }

    @Test
    @DisplayName("Warning should not trigger if the projected tail wait is within the warning threshold")
    void warningShouldNotTriggerWhenWithinThreshold() {
      // Given
      TrafficShaperConfig<TestState> config = TrafficShaperConfig.<TestState>builder("test")
          .withStrategy(strategy)
          .throttleMode(ThrottleMode.SHAPE_UNBOUNDED)
          .unboundedWarnAfter(Duration.ofSeconds(10))
          .build();

      // Tail wait is 5 seconds
      TestState state = new TestState(0, 0, 0, 0, Duration.ofSeconds(5));

      // When
      boolean warn = strategy.checkUnboundedWarning(state, config, now);

      // Then
      assertThat(warn).isFalse();
    }
  }

  // --- Dummy Implementations for testing the Interface default methods ---

  private record TestState(
      long epoch,
      int queueDepth,
      long totalAdmitted,
      long totalRejected,
      Duration tailWait
  ) implements SchedulingState {

    @Override
    public Duration projectedTailWait(Instant now) {
      return tailWait;
    }
  }

  private static class TestStrategy implements SchedulingStrategy<TestState> {
    @Override
    public TestState initial(TrafficShaperConfig<TestState> config, Instant now) {
      return null;
    }

    @Override
    public ThrottlePermission<TestState> schedule(TestState state, TrafficShaperConfig<TestState> config, Instant now) {
      return null;
    }

    @Override
    public TestState recordExecution(TestState state) {
      return null;
    }

    @Override
    public TestState reset(TestState state, TrafficShaperConfig<TestState> config, Instant now) {
      return null;
    }

    @Override
    public Duration estimateWait(TestState state, TrafficShaperConfig<TestState> config, Instant now) {
      return Duration.ZERO;
    }

    @Override
    public int queueDepth(TestState state) {
      return state.queueDepth();
    }

    @Override
    public boolean isUnboundedQueueWarning(TestState state, TrafficShaperConfig<TestState> config, Instant now) {
      return checkUnboundedWarning(state, config, now);
    }
  }
}
