package eu.inqudium.core.element.fallback.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Tests for the Fallback DSL Facade with Compile-Time Safety")
class FallbackDslTest {

    @Nested
    @DisplayName("Action Execution via Supplier")
    class SupplierActionExecution {

        @Test
        @DisplayName("The DSL should correctly map a simple Supplier action and return the static fallback value")
        void theDslShouldCorrectlyMapASimpleSupplierActionAndReturnTheStaticFallbackValue() {
            // Given
            String expectedStaticValue = "DEFAULT_CACHE_VALUE";
            TimeoutException simulatedError = new TimeoutException("Connection timed out");

            // When
            // Using the Supplier variant (ignoring the exception)
            FallbackConfig<String> config = Resilience.<String>degradeWithFallback()
                    .handlingExceptions(TimeoutException.class)
                    .fallingBackTo(() -> expectedStaticValue)
                    .apply();

            // Then
            assertThat(config.handledExceptions()).containsExactly(TimeoutException.class);

            // Verify that the internal Function executes the Supplier correctly
            String actualResult = config.fallbackAction().apply(simulatedError);
            assertThat(actualResult).isEqualTo(expectedStaticValue);
        }
    }

    @Nested
    @DisplayName("Action Execution via Function")
    class FunctionActionExecution {

        @Test
        @DisplayName("The DSL should correctly map a Function action and process the provided exception")
        void theDslShouldCorrectlyMapAFunctionActionAndProcessTheProvidedException() {
            // Given
            IOException simulatedError = new IOException("Disk full");

            // When
            // Using the Function variant to extract information from the error
            FallbackConfig<String> config = Resilience.<String>degradeWithFallback()
                    .handlingExceptions(IOException.class)
                    .fallingBackTo(cause -> "Recovered from: " + cause.getMessage())
                    .apply();

            // Then
            assertThat(config.handledExceptions()).containsExactly(IOException.class);

            // Verify that the function actually receives and processes the exception
            String actualResult = config.fallbackAction().apply(simulatedError);
            assertThat(actualResult).isEqualTo("Recovered from: Disk full");
        }
    }

    @Nested
    @DisplayName("Predefined Profiles Evaluation")
    class PredefinedProfiles {

        @Test
        @DisplayName("The DSL should apply the Universal Profile covering all Throwables while retaining the action")
        void theDslShouldApplyTheUniversalProfileCoveringAllThrowablesWhileRetainingTheAction() {
            // Given
            int expectedUniversalDefault = 42;

            // When
            FallbackConfig<Integer> config = Resilience.<Integer>degradeWithFallback()
                    .fallingBackTo(() -> expectedUniversalDefault)
                    .applyUniversalProfile();

            // Then
            assertThat(config.handledExceptions()).containsExactly(Throwable.class);
            assertThat(config.ignoredExceptions()).isEmpty();

            Integer actualResult = config.fallbackAction().apply(new RuntimeException("Fatal error"));
            assertThat(actualResult).isEqualTo(expectedUniversalDefault);
        }

        @Test
        @DisplayName("The DSL should apply the Safe Profile ignoring programmatic logic errors")
        void theDslShouldApplyTheSafeProfileIgnoringProgrammaticLogicErrors() {
            // When
            FallbackConfig<Boolean> config = Resilience.<Boolean>degradeWithFallback()
                    .fallingBackTo(() -> false)
                    .applySafeProfile();

            // Then
            assertThat(config.handledExceptions()).containsExactly(Exception.class);
            assertThat(config.ignoredExceptions())
                    .containsExactly(IllegalArgumentException.class, IllegalStateException.class);

            Boolean actualResult = config.fallbackAction().apply(new Exception("Standard error"));
            assertThat(actualResult).isFalse();
        }
    }

    @Nested
    @DisplayName("Exception Filtering Rules")
    class ExceptionFiltering {

        @Test
        @DisplayName("The DSL should correctly store ignored and handled exceptions concurrently")
        void theDslShouldCorrectlyStoreIgnoredAndHandledExceptionsConcurrently() {
            // When
            FallbackConfig<String> config = Resilience.<String>degradeWithFallback()
                    .handlingExceptions(IOException.class, TimeoutException.class)
                    .ignoringExceptions(NullPointerException.class)
                    .fallingBackTo(() -> "Fallback")
                    .apply();

            // Then
            assertThat(config.handledExceptions())
                    .containsExactly(IOException.class, TimeoutException.class);
            assertThat(config.ignoredExceptions())
                    .containsExactly(NullPointerException.class);
        }
    }
}
