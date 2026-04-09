package eu.inqudium.core.element.circuitbreaker.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static eu.inqudium.core.element.circuitbreaker.dsl.FailureTrackingStrategy.errorTracking;
import static eu.inqudium.core.element.circuitbreaker.dsl.Resilience.stabilizeWithCircuitBreaker;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Circuit Breaker Configuration DSL Tests")
class ResilienceDslTest {

  @Nested
  @DisplayName("Configuration using complete custom tracking parameters")
  class CustomTrackingEvaluation {

    @Test
    @DisplayName("The DSL should seamlessly integrate custom count based error tracking into the circuit breaker config")
    void theDslShouldSeamlesslyIntegrateCustomCountBasedErrorTrackingIntoTheCircuitBreakerConfig() {
      // Given
      int customThreshold = 75;
      Duration waitDuration = Duration.ofSeconds(15);
      int halfOpenCalls = 5;
      int historySize = 200;
      int minCalls = 30;

      // When
      CircuitBreakerConfig config = stabilizeWithCircuitBreaker()
          .named("test-2")
          .trippingAtThreshold(customThreshold)
          .waitingInOpenStateFor(waitDuration)
          .permittingCallsInHalfOpen(halfOpenCalls)
          .evaluatedBy(
              errorTracking()
                  .byCountingCalls()
                  .keepingHistoryOf(historySize)
                  .requiringAtLeast(minCalls)
              // The Builder is passed directly, no apply() needed here
          )
          .apply();

      // Then
      assertThat(config.failureThreshold()).isEqualTo(customThreshold);
      assertThat(config.waitDurationInOpenState()).isEqualTo(waitDuration);
      assertThat(config.permittedNumberOfCallsInHalfOpenState()).isEqualTo(halfOpenCalls);

      assertThat(config.metricsConfig()).isInstanceOf(SlidingWindowConfig.class);
      SlidingWindowConfig metricsConfig = (SlidingWindowConfig) config.metricsConfig();
      assertThat(metricsConfig.windowSize()).isEqualTo(historySize);
      assertThat(metricsConfig.minimumNumberOfCalls()).isEqualTo(minCalls);
    }
  }

  @Nested
  @DisplayName("Configuration using predefined metric profiles")
  class ProfileTrackingEvaluation {

    @Test
    @DisplayName("The DSL should accept a preconfigured profile correctly applying the predefined values")
    void theDslShouldAcceptAPreconfiguredProfileCorrectlyApplyingThePredefinedValues() {
      // Given
      int standardThreshold = 50;

      // When
      CircuitBreakerConfig config = stabilizeWithCircuitBreaker()
          .named("test-2")
          .trippingAtThreshold(standardThreshold)
          .evaluatedBy(
              errorTracking()
                  .byTimeWindow()
                  .applyBalancedProfile() // This terminal operation returns the record directly
          )
          .apply();

      // Then
      assertThat(config.failureThreshold()).isEqualTo(standardThreshold);
      assertThat(config.metricsConfig()).isInstanceOf(TimeBasedSlidingWindowConfig.class);

      TimeBasedSlidingWindowConfig metricsConfig = (TimeBasedSlidingWindowConfig) config.metricsConfig();
      assertThat(metricsConfig.windowSizeInSeconds()).isEqualTo(60); // Assuming 60 is the balanced default
    }
  }

  @Nested
  @DisplayName("Safety and validation checks")
  class ValidationChecks {

    @Test
    @DisplayName("The DSL should throw an exception if the metrics configuration is missing upon apply")
    void theDslShouldThrowAnExceptionIfTheMetricsConfigurationIsMissingUponApply() {
      // Given
      CircuitBreakerProtection incompleteDsl = stabilizeWithCircuitBreaker()
          .named("test-2")
          .trippingAtThreshold(50);

      // When / Then
      assertThatThrownBy(incompleteDsl::apply)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("A FailureMetricsConfig must be provided");
    }
  }
}
