package eu.inqudium.core.element.circuitbreaker.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static eu.inqudium.core.element.circuitbreaker.dsl.Resilience.stabilizeWithCircuitBreaker;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Tests for the Circuit Breaker DSL Facade with Step-Builder and Branching")
class CircuitBreakerDslTest {

  @Nested
  @DisplayName("Naming and Initialization Requirements")
  class NamingRequirements {

    @Test
    @DisplayName("The DSL should correctly assign the mandatory name to the configuration record")
    void theDslShouldCorrectlyAssignTheMandatoryNameToTheConfigurationRecord() {
      // Given
      String expectedName = "payment-service";

      // When
      CircuitBreakerConfig config = stabilizeWithCircuitBreaker()
          .named(expectedName)
          .evaluatingByCountingCalls()
          .applyBalancedProfile();

      // Then
      assertThat(config.name()).isEqualTo(expectedName);
    }

    @Test
    @DisplayName("The DSL should throw an exception if the provided name is null or blank")
    void theDslShouldThrowAnExceptionIfTheProvidedNameIsNullOrBlank() {
      // Given
      String invalidName = "   ";

      // When / Then
      assertThatThrownBy(() -> stabilizeWithCircuitBreaker().named(invalidName))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("name must not be blank");
    }
  }

  @Nested
  @DisplayName("Exception Filtering Rules")
  class ExceptionFiltering {

    @Test
    @DisplayName("The DSL should correctly map penalized and tolerated exceptions simultaneously")
    void theDslShouldCorrectlyMapPenalizedAndToleratedExceptionsSimultaneously() {
      // Given
      String serviceName = "inventory-service";

      // When
      CircuitBreakerConfig config = stabilizeWithCircuitBreaker()
          .named(serviceName)
          .failingOn(IOException.class, TimeoutException.class)
          .ignoringOn(IllegalArgumentException.class, IllegalStateException.class)
          .evaluatingByCountingCalls()
          .applyBalancedProfile();

      // Then
      assertThat(config.penalizedExceptions())
          .containsExactly(IOException.class, TimeoutException.class);
      assertThat(config.toleratedExceptions())
          .containsExactly(IllegalArgumentException.class, IllegalStateException.class);
    }
  }

  @Nested
  @DisplayName("Count-Based Evaluation Branching")
  class CountBasedEvaluation {

    @Test
    @DisplayName("The DSL should correctly map a custom count-based metric configuration")
    void theDslShouldCorrectlyMapACustomCountBasedMetricConfiguration() {
      // Given
      int customThreshold = 65;
      Duration customWaitDuration = Duration.ofSeconds(15);
      int historySize = 200;
      int minimumCalls = 50;

      // When
      CircuitBreakerConfig config = stabilizeWithCircuitBreaker()
          .named("custom-count-service")
          .trippingAtThreshold(customThreshold)
          .waitingInOpenStateFor(customWaitDuration)
          .evaluatingByCountingCalls() // Branching into count-based
          .keepingHistoryOf(historySize)
          .requiringAtLeast(minimumCalls)
          .apply();

      // Then
      assertThat(config.failureThreshold()).isEqualTo(customThreshold);
      assertThat(config.waitDurationInOpenState()).isEqualTo(customWaitDuration);

      assertThat(config.metricsConfig()).isInstanceOf(SlidingWindowConfig.class);
      SlidingWindowConfig metricsConfig = (SlidingWindowConfig) config.metricsConfig();

      assertThat(metricsConfig.windowSize()).isEqualTo(historySize);
      assertThat(metricsConfig.minimumNumberOfCalls()).isEqualTo(minimumCalls);
    }

    @Test
    @DisplayName("The DSL should correctly apply the predefined permissive profile for count-based tracking")
    void theDslShouldCorrectlyApplyThePredefinedPermissiveProfileForCountBasedTracking() {
      // When
      CircuitBreakerConfig config = stabilizeWithCircuitBreaker()
          .named("permissive-count-service")
          .evaluatingByCountingCalls()
          .applyPermissiveProfile();

      // Then
      assertThat(config.metricsConfig()).isInstanceOf(SlidingWindowConfig.class);
      SlidingWindowConfig metricsConfig = (SlidingWindowConfig) config.metricsConfig();

      assertThat(metricsConfig.windowSize()).isEqualTo(200);
      assertThat(metricsConfig.minimumNumberOfCalls()).isEqualTo(50);
    }
  }

  @Nested
  @DisplayName("Time-Based Evaluation Branching")
  class TimeBasedEvaluation {

    @Test
    @DisplayName("The DSL should correctly map a custom time-based metric configuration")
    void theDslShouldCorrectlyMapACustomTimeBasedMetricConfiguration() {
      // Given
      int expectedSeconds = 120;

      // When
      CircuitBreakerConfig config = stabilizeWithCircuitBreaker()
          .named("custom-time-service")
          .trippingAtThreshold(40)
          .evaluatingByTimeWindow() // Branching into time-based
          .lookingAtTheLast(expectedSeconds)
          .apply();

      // Then
      assertThat(config.failureThreshold()).isEqualTo(40);

      assertThat(config.metricsConfig()).isInstanceOf(TimeBasedSlidingWindowConfig.class);
      TimeBasedSlidingWindowConfig metricsConfig = (TimeBasedSlidingWindowConfig) config.metricsConfig();

      assertThat(metricsConfig.windowSizeInSeconds()).isEqualTo(expectedSeconds);
    }

    @Test
    @DisplayName("The DSL should correctly apply the predefined protective profile for time-based tracking")
    void theDslShouldCorrectlyApplyThePredefinedProtectiveProfileForTimeBasedTracking() {
      // When
      CircuitBreakerConfig config = stabilizeWithCircuitBreaker()
          .named("protective-time-service")
          .evaluatingByTimeWindow()
          .applyProtectiveProfile();

      // Then
      assertThat(config.metricsConfig()).isInstanceOf(TimeBasedSlidingWindowConfig.class);
      TimeBasedSlidingWindowConfig metricsConfig = (TimeBasedSlidingWindowConfig) config.metricsConfig();

      assertThat(metricsConfig.windowSizeInSeconds()).isEqualTo(5);
    }
  }
}
