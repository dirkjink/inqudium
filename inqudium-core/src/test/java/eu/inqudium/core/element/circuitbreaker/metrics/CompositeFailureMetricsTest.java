package eu.inqudium.core.element.circuitbreaker.metrics;

import eu.inqudium.core.element.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Composite Failure Metrics")
class CompositeFailureMetricsTest {

  private static final Instant DUMMY_TIME = Instant.parse("2026-01-01T12:00:00Z");

  private static CircuitBreakerConfig dummyConfig() {
    return CircuitBreakerConfig.builder("composite-test")
        .failureThreshold(5)
        .build();
  }

  /**
   * A pure, immutable stub implementation of FailureMetrics to isolate
   * the tests from the actual behavior of real metric algorithms.
   */
  private record StubMetrics(
      int successCount,
      int failureCount,
      boolean shouldTrip
  ) implements FailureMetrics {

    static StubMetrics initial(boolean shouldTrip) {
      return new StubMetrics(0, 0, shouldTrip);
    }

    @Override
    public FailureMetrics recordSuccess(Instant now) {
      return new StubMetrics(successCount + 1, failureCount, shouldTrip);
    }

    @Override
    public FailureMetrics recordFailure(Instant now) {
      return new StubMetrics(successCount, failureCount + 1, shouldTrip);
    }

    @Override
    public boolean isThresholdReached(CircuitBreakerConfig config, Instant now) {
      return shouldTrip;
    }

    @Override
    public FailureMetrics reset(Instant now) {
      // Returns a fresh state, dropping the counts to 0, but preserving the mock behavior
      return new StubMetrics(0, 0, shouldTrip);
    }

    @Override
    public String getTripReason(CircuitBreakerConfig config, Instant now) {
      return "";
    }
  }

  @Nested
  @DisplayName("Initialization")
  class Initialization {

    @Test
    @DisplayName("should initialize correctly when multiple valid metrics are provided")
    void should_initialize_correctly_when_multiple_valid_metrics_are_provided() {
      // Given
      FailureMetrics stub1 = StubMetrics.initial(false);
      FailureMetrics stub2 = StubMetrics.initial(false);

      // When
      CompositeFailureMetrics composite = CompositeFailureMetrics.of(stub1, stub2);

      // Then
      assertThat(composite.delegates()).hasSize(2);
      assertThat(composite.delegates()).containsExactly(stub1, stub2);
    }

    @Test
    @DisplayName("should throw an exception when initialized with null")
    void should_throw_an_exception_when_initialized_with_null() {
      // Given / When / Then
      assertThatThrownBy(() -> CompositeFailureMetrics.of((FailureMetrics[]) null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("At least one FailureMetrics instance must be provided");
    }

    @Test
    @DisplayName("should throw an exception when initialized with an empty array")
    void should_throw_an_exception_when_initialized_with_an_empty_array() {
      // Given / When / Then
      assertThatThrownBy(CompositeFailureMetrics::of)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("At least one FailureMetrics instance must be provided");
    }
  }

  @Nested
  @DisplayName("Recording Outcomes")
  class RecordingOutcomes {

    @Test
    @DisplayName("should propagate success recording to all delegated metrics")
    void should_propagate_success_recording_to_all_delegated_metrics() {
      // Given
      FailureMetrics stub1 = StubMetrics.initial(false);
      FailureMetrics stub2 = StubMetrics.initial(false);
      FailureMetrics composite = CompositeFailureMetrics.of(stub1, stub2);

      // When
      FailureMetrics updatedComposite = composite.recordSuccess(DUMMY_TIME);

      // Then
      CompositeFailureMetrics updated = (CompositeFailureMetrics) updatedComposite;
      assertThat(updated.delegates()).hasSize(2);

      StubMetrics updatedStub1 = (StubMetrics) updated.delegates().get(0);
      StubMetrics updatedStub2 = (StubMetrics) updated.delegates().get(1);

      assertThat(updatedStub1.successCount()).isEqualTo(1);
      assertThat(updatedStub2.successCount()).isEqualTo(1);
      assertThat(updatedStub1.failureCount()).isZero();
    }

    @Test
    @DisplayName("should propagate failure recording to all delegated metrics")
    void should_propagate_failure_recording_to_all_delegated_metrics() {
      // Given
      FailureMetrics stub1 = StubMetrics.initial(false);
      FailureMetrics stub2 = StubMetrics.initial(false);
      FailureMetrics composite = CompositeFailureMetrics.of(stub1, stub2);

      // When
      FailureMetrics updatedComposite = composite
          .recordFailure(DUMMY_TIME)
          .recordFailure(DUMMY_TIME);

      // Then
      CompositeFailureMetrics updated = (CompositeFailureMetrics) updatedComposite;

      StubMetrics updatedStub1 = (StubMetrics) updated.delegates().get(0);
      StubMetrics updatedStub2 = (StubMetrics) updated.delegates().get(1);

      assertThat(updatedStub1.failureCount()).isEqualTo(2);
      assertThat(updatedStub2.failureCount()).isEqualTo(2);
      assertThat(updatedStub1.successCount()).isZero();
    }
  }

  @Nested
  @DisplayName("Threshold Evaluation")
  class ThresholdEvaluation {

    @Test
    @DisplayName("should return true when the first delegated metric trips")
    void should_return_true_when_the_first_delegated_metric_trips() {
      // Given
      FailureMetrics trippingStub = StubMetrics.initial(true);
      FailureMetrics quietStub = StubMetrics.initial(false);
      FailureMetrics composite = CompositeFailureMetrics.of(trippingStub, quietStub);

      // When
      boolean isTripped = composite.isThresholdReached(dummyConfig(), DUMMY_TIME);

      // Then
      assertThat(isTripped).isTrue();
    }

    @Test
    @DisplayName("should return true when the second delegated metric trips")
    void should_return_true_when_the_second_delegated_metric_trips() {
      // Given
      FailureMetrics quietStub = StubMetrics.initial(false);
      FailureMetrics trippingStub = StubMetrics.initial(true);
      FailureMetrics composite = CompositeFailureMetrics.of(quietStub, trippingStub);

      // When
      boolean isTripped = composite.isThresholdReached(dummyConfig(), DUMMY_TIME);

      // Then
      assertThat(isTripped).isTrue();
    }

    @Test
    @DisplayName("should return false when none of the delegated metrics trip")
    void should_return_false_when_none_of_the_delegated_metrics_trip() {
      // Given
      FailureMetrics quietStub1 = StubMetrics.initial(false);
      FailureMetrics quietStub2 = StubMetrics.initial(false);
      FailureMetrics composite = CompositeFailureMetrics.of(quietStub1, quietStub2);

      // When
      boolean isTripped = composite.isThresholdReached(dummyConfig(), DUMMY_TIME);

      // Then
      assertThat(isTripped).isFalse();
    }
  }

  @Nested
  @DisplayName("Resetting State")
  class ResettingState {

    @Test
    @DisplayName("should reset all delegated metrics to their initial state")
    void should_reset_all_delegated_metrics_to_their_initial_state() {
      // Given
      FailureMetrics stub1 = StubMetrics.initial(false);
      FailureMetrics stub2 = StubMetrics.initial(false);
      FailureMetrics composite = CompositeFailureMetrics.of(stub1, stub2)
          .recordFailure(DUMMY_TIME)
          .recordSuccess(DUMMY_TIME);

      // When
      FailureMetrics resetComposite = composite.reset(DUMMY_TIME);

      // Then
      CompositeFailureMetrics reset = (CompositeFailureMetrics) resetComposite;

      StubMetrics resetStub1 = (StubMetrics) reset.delegates().get(0);
      StubMetrics resetStub2 = (StubMetrics) reset.delegates().get(1);

      assertThat(resetStub1.failureCount()).isZero();
      assertThat(resetStub1.successCount()).isZero();

      assertThat(resetStub2.failureCount()).isZero();
      assertThat(resetStub2.successCount()).isZero();
    }
  }
}
