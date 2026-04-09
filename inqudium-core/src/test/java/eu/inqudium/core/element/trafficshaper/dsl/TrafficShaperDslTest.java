package eu.inqudium.core.element.trafficshaper.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static eu.inqudium.core.element.trafficshaper.dsl.Resilience.shapeWithTrafficShaper;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Tests for the Traffic Shaper DSL Facade")
class TrafficShaperDslTest {

  @Nested
  @DisplayName("Custom Configuration Evaluation")
  class CustomConfiguration {

    @Test
    @DisplayName("The DSL should correctly map custom modifiers to the Traffic Shaper configuration record")
    void theDslShouldCorrectlyMapCustomModifiersToTheTrafficShaperConfigurationRecord() {
      // Given
      int expectedCalls = 75;
      Duration expectedPeriod = Duration.ofMinutes(1);
      Duration expectedWaitDuration = Duration.ofSeconds(15);

      // When
      TrafficShaperConfig config = shapeWithTrafficShaper()
          .permittingCalls(expectedCalls)
          .withinPeriod(expectedPeriod)
          .queueingForAtMost(expectedWaitDuration)
          .apply();

      // Then
      assertThat(config.permittedCalls()).isEqualTo(expectedCalls);
      assertThat(config.evaluationPeriod()).isEqualTo(expectedPeriod);
      assertThat(config.maxWaitDuration()).isEqualTo(expectedWaitDuration);
    }
  }

  @Nested
  @DisplayName("Predefined Profiles Evaluation")
  class PredefinedProfiles {

    @Test
    @DisplayName("The DSL should apply the Strict Profile correctly dropping traffic over the limit immediately")
    void theDslShouldApplyTheStrictProfileCorrectlyDroppingTrafficOverTheLimitImmediately() {
      // When
      TrafficShaperConfig config = shapeWithTrafficShaper().applyStrictProfile();

      // Then
      assertThat(config.permittedCalls()).isEqualTo(10);
      assertThat(config.evaluationPeriod()).isEqualTo(Duration.ofSeconds(1));
      assertThat(config.maxWaitDuration()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("The DSL should apply the Balanced Profile correctly to smooth out normal traffic bursts")
    void theDslShouldApplyTheBalancedProfileCorrectlyToSmoothOutNormalTrafficBursts() {
      // When
      TrafficShaperConfig config = shapeWithTrafficShaper().applyBalancedProfile();

      // Then
      assertThat(config.permittedCalls()).isEqualTo(100);
      assertThat(config.evaluationPeriod()).isEqualTo(Duration.ofSeconds(1));
      assertThat(config.maxWaitDuration()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("The DSL should apply the Permissive Profile correctly buffering large amounts of traffic")
    void theDslShouldApplyThePermissiveProfileCorrectlyBufferingLargeAmountsOfTraffic() {
      // When
      TrafficShaperConfig config = shapeWithTrafficShaper().applyPermissiveProfile();

      // Then
      assertThat(config.permittedCalls()).isEqualTo(500);
      assertThat(config.evaluationPeriod()).isEqualTo(Duration.ofSeconds(1));
      assertThat(config.maxWaitDuration()).isEqualTo(Duration.ofSeconds(10));
    }
  }

  @Nested
  @DisplayName("Default Fallback Evaluation")
  class DefaultFallbacks {

    @Test
    @DisplayName("The DSL should provide safe defaults if apply is called without explicit modifiers")
    void theDslShouldProvideSafeDefaultsIfApplyIsCalledWithoutExplicitModifiers() {
      // When
      TrafficShaperConfig config = shapeWithTrafficShaper().apply();

      // Then
      assertThat(config.permittedCalls()).isEqualTo(50);
      assertThat(config.evaluationPeriod()).isEqualTo(Duration.ofSeconds(1));
      assertThat(config.maxWaitDuration()).isEqualTo(Duration.ofMillis(500));
    }
  }
}
