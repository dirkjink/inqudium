package eu.inqudium.core.element.fallback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackProviderProcessorTest {

    private final FallbackProviderProcessor processor = new FallbackProviderProcessor();

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @interface CustomMarker {
    }

    static class BaseProvider {
        @FallbackData
        public static String inheritedMethod() {
            return "Inherited Result";
        }
    }

    // Used for signature matching tests
    static class SubProvider extends BaseProvider {
        @FallbackData
        public static String zeroParamMethod() {
            return "Zero Param Result";
        }

        @FallbackData
        public static String exactMatchMethod(int value) {
            return "Exact Match Result: " + value;
        }

        @CustomMarker
        public static String customMarkerMethod() {
            return "Custom Marker Result";
        }
    }

// --- Test Infrastructure ---

    // NEW: Used specifically to test inheritance
    static class EmptySubProvider extends BaseProvider {
        // No @FallbackData methods defined here!
        // The processor MUST go to BaseProvider to find one.
    }

    static class Target {
        @DataProvidedBy(source = SubProvider.class)
        public void methodWithArgs(String s) {
        }

        @DataProvidedBy(source = SubProvider.class)
        public void methodWithExactMatch(int i) {
        }

        // FIXED: Now uses the EmptySubProvider to force the hierarchy traversal
        @DataProvidedBy(source = EmptySubProvider.class)
        public void methodForInheritedFallback() {
        }

        @DataProvidedBy(source = SubProvider.class, marker = CustomMarker.class)
        public void methodWithCustomMarker() {
        }
    }

    @Nested
    @DisplayName("Tests for method signature matching")
    class SignatureMatchingTests {

        @Test
        void theProcessorShouldInvokeFallbackWithZeroParametersWhenAvailable() throws Exception {
            // Given
            Method target = Target.class.getDeclaredMethod("methodWithArgs", String.class);
            Object[] args = {"Input"};

            // When
            Object result = processor.resolveAndInvoke(target, args);

            // Then
            assertThat(result).isEqualTo("Zero Param Result");
        }

        @Test
        void theProcessorShouldInvokeFallbackWithExactParametersWhenAvailable() throws Exception {
            // Given
            Method target = Target.class.getDeclaredMethod("methodWithExactMatch", int.class);
            Object[] args = {42};

            // When
            Object result = processor.resolveAndInvoke(target, args);

            // Then
            assertThat(result).isEqualTo("Exact Match Result: 42");
        }
    }

    @Nested
    @DisplayName("Tests for class hierarchy and inheritance")
    class InheritanceTests {

        @Test
        void theProcessorShouldFindTheFallbackMethodInTheSuperclass() throws Exception {
            // Given
            Method target = Target.class.getDeclaredMethod("methodForInheritedFallback");

            // When
            Object result = processor.resolveAndInvoke(target, new Object[]{});

            // Then
            assertThat(result).isEqualTo("Inherited Result");
        }
    }

    @Nested
    @DisplayName("Tests for custom marker annotations")
    class CustomMarkerTests {

        @Test
        void theProcessorShouldUseTheUserDefinedMarkerAnnotation() throws Exception {
            // Given
            Method target = Target.class.getDeclaredMethod("methodWithCustomMarker");

            // When
            Object result = processor.resolveAndInvoke(target, new Object[]{});

            // Then
            assertThat(result).isEqualTo("Custom Marker Result");
        }
    }
}
