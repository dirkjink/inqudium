package eu.inqudium.core.element.timelimiter.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static eu.inqudium.core.element.timelimiter.dsl.Resilience.constrainWithTimeLimiter;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Tests for the Time Limiter DSL Facade")
class TimeLimiterDslTest {

    @Nested
    @DisplayName("Custom Configuration Evaluation")
    class CustomConfiguration {

        @Test
        @DisplayName("The DSL should correctly map custom modifiers to the Time Limiter configuration record")
        void theDslShouldCorrectlyMapCustomModifiersToTheTimeLimiterConfigurationRecord() {
            // Given
            Duration expectedTimeout = Duration.ofMillis(800);
            boolean expectedCancelFlag = false;

            // When
            TimeLimiterConfig config = constrainWithTimeLimiter()
                    .timingOutAfter(expectedTimeout)
                    .cancelingRunningTasks(expectedCancelFlag)
                    .apply();

            // Then
            assertThat(config.timeoutDuration()).isEqualTo(expectedTimeout);
            assertThat(config.cancelRunningTask()).isEqualTo(expectedCancelFlag);
        }
    }

    @Nested
    @DisplayName("Predefined Profiles Evaluation")
    class PredefinedProfiles {

        @Test
        @DisplayName("The DSL should apply the Strict Profile correctly")
        void theDslShouldApplyTheStrictProfileCorrectly() {
            // When
            TimeLimiterConfig config = constrainWithTimeLimiter().applyStrictProfile();

            // Then
            assertThat(config.timeoutDuration()).isEqualTo(Duration.ofMillis(500));
            assertThat(config.cancelRunningTask()).isTrue();
        }

        @Test
        @DisplayName("The DSL should apply the Balanced Profile correctly")
        void theDslShouldApplyTheBalancedProfileCorrectly() {
            // When
            TimeLimiterConfig config = constrainWithTimeLimiter().applyBalancedProfile();

            // Then
            assertThat(config.timeoutDuration()).isEqualTo(Duration.ofSeconds(3));
            assertThat(config.cancelRunningTask()).isTrue();
        }

        @Test
        @DisplayName("The DSL should apply the Permissive Profile correctly")
        void theDslShouldApplyThePermissiveProfileCorrectly() {
            // When
            TimeLimiterConfig config = constrainWithTimeLimiter().applyPermissiveProfile();

            // Then
            assertThat(config.timeoutDuration()).isEqualTo(Duration.ofSeconds(10));
            assertThat(config.cancelRunningTask()).isFalse();
        }
    }

    @Nested
    @DisplayName("Default Fallback Evaluation")
    class DefaultFallbacks {

        @Test
        @DisplayName("The DSL should provide safe defaults if apply is called without modifiers")
        void theDslShouldProvideSafeDefaultsIfApplyIsCalledWithoutModifiers() {
            // When
            TimeLimiterConfig config = constrainWithTimeLimiter().apply();

            // Then
            assertThat(config.timeoutDuration()).isEqualTo(Duration.ofSeconds(1));
            assertThat(config.cancelRunningTask()).isTrue();
        }
    }
}