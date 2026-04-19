package eu.inqudium.core.element;

import eu.inqudium.core.element.InqElementRegistry.InqElementNotFoundException;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InternalExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InqElementRegistry")
class InqElementRegistryTest {

    // =========================================================================
    // Stub elements
    // =========================================================================

    static class StubElement implements InqDecorator<Void, Object> {
        private final String name;
        private final InqElementType type;

        StubElement(String name, InqElementType type) {
            this.name = name;
            this.type = type;
        }

        @Override public String getName() { return name; }
        @Override public InqElementType getElementType() { return type; }
        @Override public InqEventPublisher getEventPublisher() { return null; }

        @Override
        public Object execute(long chainId, long callId, Void arg,
                              InternalExecutor<Void, Object> next) {
            return next.execute(chainId, callId, arg);
        }
    }

    /**
     * A different InqElement subtype — used to verify typed lookup rejects
     * wrong subtypes at runtime.
     */
    static class OtherElement implements InqElement {
        @Override public String getName() { return "other"; }
        @Override public InqElementType getElementType() { return InqElementType.BULKHEAD; }
        @Override public InqEventPublisher getEventPublisher() { return null; }
    }

    private static StubElement cb(String name) {
        return new StubElement(name, InqElementType.CIRCUIT_BREAKER);
    }

    private static StubElement rt(String name) {
        return new StubElement(name, InqElementType.RETRY);
    }

    private static StubElement bh(String name) {
        return new StubElement(name, InqElementType.BULKHEAD);
    }

    // =========================================================================
    // Builder
    // =========================================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        void builds_a_registry_with_pre_registered_elements() {
            // Given
            var cbElement = cb("paymentCb");
            var rtElement = rt("paymentRetry");

            // When
            InqElementRegistry registry = InqElementRegistry.builder()
                    .register("paymentCb", cbElement)
                    .register("paymentRetry", rtElement)
                    .build();

            // Then
            assertThat(registry.size()).isEqualTo(2);
            assertThat(registry.get("paymentCb")).isSameAs(cbElement);
            assertThat(registry.get("paymentRetry")).isSameAs(rtElement);
        }

        @Test
        void builds_an_empty_registry() {
            // When
            InqElementRegistry registry = InqElementRegistry.builder().build();

            // Then
            assertThat(registry.isEmpty()).isTrue();
            assertThat(registry.size()).isZero();
        }

        @Test
        void builder_rejects_null_name() {
            assertThatThrownBy(() -> InqElementRegistry.builder().register(null, cb("cb")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void builder_rejects_blank_name() {
            assertThatThrownBy(() -> InqElementRegistry.builder().register("  ", cb("cb")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void builder_rejects_null_element() {
            assertThatThrownBy(() -> InqElementRegistry.builder().register("cb", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // Registration (mutable)
    // =========================================================================

    @Nested
    @DisplayName("register")
    class Registration {

        @Test
        void registers_and_retrieves_an_element() {
            // Given
            InqElementRegistry registry = InqElementRegistry.create();
            var element = cb("paymentCb");

            // When
            registry.register("paymentCb", element);

            // Then
            assertThat(registry.get("paymentCb")).isSameAs(element);
        }

        @Test
        void replaces_existing_element_and_returns_previous() {
            // Given
            InqElementRegistry registry = InqElementRegistry.create();
            var first = cb("v1");
            var second = cb("v2");
            registry.register("cb", first);

            // When
            InqElement previous = registry.register("cb", second);

            // Then
            assertThat(previous).isSameAs(first);
            assertThat(registry.get("cb")).isSameAs(second);
            assertThat(registry.size()).isEqualTo(1);
        }

        @Test
        void returns_null_when_no_previous_element() {
            // Given
            InqElementRegistry registry = InqElementRegistry.create();

            // When
            InqElement previous = registry.register("cb", cb("cb"));

            // Then
            assertThat(previous).isNull();
        }
    }

    // =========================================================================
    // Lookup
    // =========================================================================

    @Nested
    @DisplayName("get")
    class GetTests {

        @Test
        void throws_descriptive_exception_when_element_not_found() {
            // Given
            InqElementRegistry registry = InqElementRegistry.builder()
                    .register("existingCb", cb("existingCb"))
                    .build();

            // When / Then
            assertThatThrownBy(() -> registry.get("unknownCb"))
                    .isInstanceOf(InqElementNotFoundException.class)
                    .hasMessageContaining("unknownCb")
                    .hasMessageContaining("existingCb")
                    .satisfies(ex -> {
                        var notFound = (InqElementNotFoundException) ex;
                        assertThat(notFound.name()).isEqualTo("unknownCb");
                    });
        }

        @Test
        void throws_on_empty_registry_with_none_hint() {
            // Given
            InqElementRegistry registry = InqElementRegistry.create();

            // When / Then
            assertThatThrownBy(() -> registry.get("anything"))
                    .isInstanceOf(InqElementNotFoundException.class)
                    .hasMessageContaining("(none)");
        }
    }

    @Nested
    @DisplayName("get with type")
    class GetTyped {

        @Test
        void returns_element_cast_to_the_expected_type() {
            // Given
            InqElementRegistry registry = InqElementRegistry.builder()
                    .register("cb", cb("cb"))
                    .build();

            // When
            StubElement element = registry.get("cb", StubElement.class);

            // Then
            assertThat(element.getName()).isEqualTo("cb");
        }

        @Test
        void throws_class_cast_exception_for_wrong_subtype() {
            // Given — registered as StubElement
            InqElementRegistry registry = InqElementRegistry.builder()
                    .register("cb", cb("cb"))
                    .build();

            // When / Then — requesting as OtherElement
            assertThatThrownBy(() -> registry.get("cb", OtherElement.class))
                    .isInstanceOf(ClassCastException.class)
                    .hasMessageContaining("cb")
                    .hasMessageContaining("OtherElement");
        }
    }

    @Nested
    @DisplayName("find")
    class FindTests {

        @Test
        void returns_present_optional_for_existing_element() {
            // Given
            var element = cb("cb");
            InqElementRegistry registry = InqElementRegistry.builder()
                    .register("cb", element)
                    .build();

            // When / Then
            assertThat(registry.find("cb"))
                    .isPresent()
                    .containsSame(element);
        }

        @Test
        void returns_empty_optional_for_missing_element() {
            // Given
            InqElementRegistry registry = InqElementRegistry.create();

            // When / Then
            assertThat(registry.find("unknown")).isEmpty();
        }
    }

    @Nested
    @DisplayName("contains")
    class ContainsTests {

        @Test
        void returns_true_for_registered_element() {
            // Given
            InqElementRegistry registry = InqElementRegistry.builder()
                    .register("cb", cb("cb"))
                    .build();

            // Then
            assertThat(registry.contains("cb")).isTrue();
        }

        @Test
        void returns_false_for_unknown_name() {
            assertThat(InqElementRegistry.create().contains("unknown")).isFalse();
        }

        @Test
        void returns_false_for_null_name() {
            assertThat(InqElementRegistry.create().contains(null)).isFalse();
        }
    }

    // =========================================================================
    // Introspection
    // =========================================================================

    @Nested
    @DisplayName("Introspection")
    class Introspection {

        @Test
        void names_returns_all_registered_names() {
            // Given
            InqElementRegistry registry = InqElementRegistry.builder()
                    .register("cb", cb("cb"))
                    .register("rt", rt("rt"))
                    .register("bh", bh("bh"))
                    .build();

            // Then
            assertThat(registry.names()).containsExactlyInAnyOrder("cb", "rt", "bh");
        }

        @Test
        void elements_returns_all_registered_elements() {
            // Given
            var cbEl = cb("cb");
            var rtEl = rt("rt");
            InqElementRegistry registry = InqElementRegistry.builder()
                    .register("cb", cbEl)
                    .register("rt", rtEl)
                    .build();

            // Then
            assertThat(registry.elements()).containsExactlyInAnyOrder(cbEl, rtEl);
        }

        @Test
        void to_string_shows_element_count_and_names() {
            // Given
            InqElementRegistry registry = InqElementRegistry.builder()
                    .register("paymentCb", cb("paymentCb"))
                    .register("paymentRetry", rt("paymentRetry"))
                    .build();

            // Then
            assertThat(registry.toString())
                    .contains("2 elements")
                    .contains("paymentCb")
                    .contains("paymentRetry");
        }
    }
}
