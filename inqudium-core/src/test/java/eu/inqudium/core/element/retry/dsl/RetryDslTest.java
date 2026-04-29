package eu.inqudium.core.element.retry.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static eu.inqudium.core.element.retry.dsl.Resilience.recoverWithRetry;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Tests for the Retry DSL Facade")
class RetryDslTest {

    @Nested
    @DisplayName("Custom Configuration Evaluation")
    class CustomConfiguration {

        @Test
        @DisplayName("The DSL should correctly map custom modifiers to the Retry configuration record")
        void theDslShouldCorrectlyMapCustomModifiersToTheRetryConfigurationRecord() {
            // Given
            int expectedAttempts = 5;
            Duration expectedWait = Duration.ofSeconds(2);
            double expectedMultiplier = 2.5;

            // When
            RetryConfig config = recoverWithRetry()
                    .attemptingUpTo(expectedAttempts)
                    .waitingBetweenAttempts(expectedWait)
                    .backingOffExponentially(expectedMultiplier)
                    .apply();

            // Then
            assertThat(config.maxAttempts()).isEqualTo(expectedAttempts);
            assertThat(config.baseWaitDuration()).isEqualTo(expectedWait);
            assertThat(config.backoffMultiplier()).isEqualTo(expectedMultiplier);
        }
    }

    @Nested
    @DisplayName("Predefined Profiles Evaluation")
    class PredefinedProfiles {

        @Test
        @DisplayName("The DSL should apply the Strict Profile correctly for fast-failing scenarios")
        void theDslShouldApplyTheStrictProfileCorrectlyForFastFailingScenarios() {
            // When
            RetryConfig config = recoverWithRetry().applyStrictProfile();

            // Then
            assertThat(config.maxAttempts()).isEqualTo(2);
            assertThat(config.baseWaitDuration()).isEqualTo(Duration.ofMillis(100));
            assertThat(config.backoffMultiplier()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("The DSL should apply the Balanced Profile correctly using an industry standard exponential backoff")
        void theDslShouldApplyTheBalancedProfileCorrectlyUsingAnIndustryStandardExponentialBackoff() {
            // When
            RetryConfig config = recoverWithRetry().applyBalancedProfile();

            // Then
            assertThat(config.maxAttempts()).isEqualTo(3);
            assertThat(config.baseWaitDuration()).isEqualTo(Duration.ofMillis(500));
            assertThat(config.backoffMultiplier()).isEqualTo(1.5);
        }

        @Test
        @DisplayName("The DSL should apply the Permissive Profile correctly for persistent background tasks")
        void theDslShouldApplyThePermissiveProfileCorrectlyForPersistentBackgroundTasks() {
            // When
            RetryConfig config = recoverWithRetry().applyPermissiveProfile();

            // Then
            assertThat(config.maxAttempts()).isEqualTo(10);
            assertThat(config.baseWaitDuration()).isEqualTo(Duration.ofSeconds(1));
            assertThat(config.backoffMultiplier()).isEqualTo(2.0);
        }
    }

    @Nested
    @DisplayName("Default Fallback Evaluation")
    class DefaultFallbacks {

        @Test
        @DisplayName("The DSL should provide safe defaults if apply is called without explicit modifiers")
        void theDslShouldProvideSafeDefaultsIfApplyIsCalledWithoutExplicitModifiers() {
            // When
            RetryConfig config = recoverWithRetry().apply();

            // Then
            assertThat(config.maxAttempts()).isEqualTo(3);
            assertThat(config.baseWaitDuration()).isEqualTo(Duration.ofMillis(500));
            assertThat(config.backoffMultiplier()).isEqualTo(1.0);
        }
    }
}