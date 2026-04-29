package eu.inqudium.core.element.ratelimiter.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static eu.inqudium.core.element.ratelimiter.dsl.Resilience.throttleWithRateLimiter;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Tests for the Rate Limiter DSL Facade")
class RateLimiterDslTest {

    @Nested
    @DisplayName("Custom Configuration Evaluation")
    class CustomConfiguration {

        @Test
        @DisplayName("The DSL should correctly map custom modifiers to the Rate Limiter configuration record")
        void theDslShouldCorrectlyMapCustomModifiersToTheRateLimiterConfigurationRecord() {
            // Given
            int expectedLimit = 250;
            Duration expectedRefreshPeriod = Duration.ofSeconds(10);
            Duration expectedWaitDuration = Duration.ofMillis(200);

            // When
            RateLimiterConfig config = throttleWithRateLimiter()
                    .allowingCalls(expectedLimit)
                    .refreshingLimitEvery(expectedRefreshPeriod)
                    .waitingForPermissionAtMost(expectedWaitDuration)
                    .apply();

            // Then
            assertThat(config.limitForPeriod()).isEqualTo(expectedLimit);
            assertThat(config.limitRefreshPeriod()).isEqualTo(expectedRefreshPeriod);
            assertThat(config.timeoutDuration()).isEqualTo(expectedWaitDuration);
        }
    }

    @Nested
    @DisplayName("Predefined Profiles Evaluation")
    class PredefinedProfiles {

        @Test
        @DisplayName("The DSL should apply the Strict Profile correctly ensuring a fail-fast behavior")
        void theDslShouldApplyTheStrictProfileCorrectlyEnsuringAFailFastBehavior() {
            // When
            RateLimiterConfig config = throttleWithRateLimiter().applyStrictProfile();

            // Then
            assertThat(config.limitForPeriod()).isEqualTo(10);
            assertThat(config.limitRefreshPeriod()).isEqualTo(Duration.ofSeconds(1));
            assertThat(config.timeoutDuration()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("The DSL should apply the Balanced Profile correctly allowing a short wait time for tokens")
        void theDslShouldApplyTheBalancedProfileCorrectlyAllowingAShortWaitTimeForTokens() {
            // When
            RateLimiterConfig config = throttleWithRateLimiter().applyBalancedProfile();

            // Then
            assertThat(config.limitForPeriod()).isEqualTo(100);
            assertThat(config.limitRefreshPeriod()).isEqualTo(Duration.ofSeconds(1));
            assertThat(config.timeoutDuration()).isEqualTo(Duration.ofMillis(500));
        }

        @Test
        @DisplayName("The DSL should apply the Permissive Profile correctly for high burst capacities")
        void theDslShouldApplyThePermissiveProfileCorrectlyForHighBurstCapacities() {
            // When
            RateLimiterConfig config = throttleWithRateLimiter().applyPermissiveProfile();

            // Then
            assertThat(config.limitForPeriod()).isEqualTo(1000);
            assertThat(config.limitRefreshPeriod()).isEqualTo(Duration.ofSeconds(1));
            assertThat(config.timeoutDuration()).isEqualTo(Duration.ofSeconds(5));
        }
    }

    @Nested
    @DisplayName("Default Fallback Evaluation")
    class DefaultFallbacks {

        @Test
        @DisplayName("The DSL should provide safe defaults if apply is called without explicit modifiers")
        void theDslShouldProvideSafeDefaultsIfApplyIsCalledWithoutExplicitModifiers() {
            // When
            RateLimiterConfig config = throttleWithRateLimiter().apply();

            // Then
            assertThat(config.limitForPeriod()).isEqualTo(50);
            assertThat(config.limitRefreshPeriod()).isEqualTo(Duration.ofSeconds(1));
            assertThat(config.timeoutDuration()).isEqualTo(Duration.ofSeconds(5));
        }
    }
}
