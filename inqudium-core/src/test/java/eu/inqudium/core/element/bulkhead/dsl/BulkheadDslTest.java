package eu.inqudium.core.element.bulkhead.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static eu.inqudium.core.element.bulkhead.dsl.Resilience.isolateWithBulkhead;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Tests for the Bulkhead DSL Facade")
class BulkheadDslTest {

    @Nested
    @DisplayName("Custom Configuration Evaluation")
    class CustomConfiguration {

        @Test
        @DisplayName("The DSL should correctly map custom modifiers to the Bulkhead configuration record")
        void theDslShouldCorrectlyMapCustomModifiersToTheBulkheadConfigurationRecord() {
            // Given
            int expectedMaxCalls = 42;
            Duration expectedWaitDuration = Duration.ofMillis(250);

            // When
            // "Isolate with bulkhead, limiting concurrent calls to 42, waiting at most for 250ms, apply."
            BulkheadConfig config = isolateWithBulkhead()
                    .named("test-1")
                    .limitingConcurrentCallsTo(expectedMaxCalls)
                    .waitingAtMostFor(expectedWaitDuration)
                    .apply();

            // Then
            assertThat(config.maxConcurrentCalls()).isEqualTo(expectedMaxCalls);
            assertThat(config.maxWaitDuration()).isEqualTo(expectedWaitDuration);
        }
    }

    @Nested
    @DisplayName("Predefined Profiles Evaluation")
    class PredefinedProfiles {

        @Test
        @DisplayName("The DSL should apply the Strict Profile correctly (Fail-Fast)")
        void theDslShouldApplyTheStrictProfileCorrectly() {
            // When
            BulkheadConfig config =
                    isolateWithBulkhead()
                            .named("test-1")
                            .applyProtectiveProfile();

            // Then
            assertThat(config.maxConcurrentCalls()).isEqualTo(10);
            assertThat(config.maxWaitDuration()).isEqualTo(Duration.ZERO); // Fail-fast guarantee
        }

        @Test
        @DisplayName("The DSL should apply the Balanced Profile correctly")
        void theDslShouldApplyTheBalancedProfileCorrectly() {
            // When
            BulkheadConfig config =
                    isolateWithBulkhead()
                            .named("test-1")
                            .applyBalancedProfile();

            // Then
            assertThat(config.maxConcurrentCalls()).isEqualTo(50);
            assertThat(config.maxWaitDuration()).isEqualTo(Duration.ofMillis(500));
        }

        @Test
        @DisplayName("The DSL should apply the Permissive Profile correctly")
        void theDslShouldApplyThePermissiveProfileCorrectly() {
            // When
            BulkheadConfig config =
                    isolateWithBulkhead()
                            .named("test-1")
                            .applyPermissiveProfile();

            // Then
            assertThat(config.maxConcurrentCalls()).isEqualTo(200);
            assertThat(config.maxWaitDuration()).isEqualTo(Duration.ofSeconds(5));
        }
    }

    @Nested
    @DisplayName("Default Fallback Evaluation")
    class DefaultFallbacks {

        @Test
        @DisplayName("The DSL should provide safe defaults if apply() is called without modifiers")
        void theDslShouldProvideSafeDefaultsIfApplyIsCalledWithoutModifiers() {
            // When
            BulkheadConfig config =
                    isolateWithBulkhead()
                            .named("test-1")
                            .apply();

            // Then
            // Verifying the safe internal defaults defined in DefaultBulkheadProtection
            assertThat(config.maxConcurrentCalls()).isEqualTo(25);
            assertThat(config.maxWaitDuration()).isEqualTo(Duration.ZERO);
        }
    }
}
