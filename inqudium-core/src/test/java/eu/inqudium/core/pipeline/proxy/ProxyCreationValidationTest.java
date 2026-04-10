package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.pipeline.LayerAction;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link ProxyWrapper#createProxy} fails fast when the
 * extension configuration is invalid, rather than deferring errors to
 * the first method invocation at runtime.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ProxyCreationValidationTest {

    // ======================== Test doubles ========================

    private static SyncDispatchExtension catchAllExtension() {
        LayerAction<Void, Object> noop = (cid, caid, a, next) -> next.execute(cid, caid, a);
        return new SyncDispatchExtension(noop);
    }

    interface GreetingService {
        String greet(String name);
    }

    static class SimpleGreeter implements GreetingService {
        @Override
        public String greet(String name) {
            return "Hello, " + name;
        }
    }

    // ======================== Helpers ========================

    /**
     * An extension that only handles specific methods — not a catch-all.
     */
    static class SelectiveExtension implements DispatchExtension {

        @Override
        public boolean canHandle(Method method) {
            return method.getName().equals("specificMethod");
        }

        @Override
        public Object dispatch(long chainId, long callId,
                               Method method, Object[] args, Object target) {
            throw new UnsupportedOperationException("not implemented in test");
        }

        @Override
        public boolean isCatchAll() {
            return false;
        }
    }

    // ======================== Tests ========================

    @Nested
    class When_no_extensions_are_provided {

        @Test
        void creation_fails_immediately_with_descriptive_message() {
            // Given
            GreetingService target = new SimpleGreeter();

            // When / Then
            assertThatThrownBy(() ->
                    ProxyWrapper.createProxy(GreetingService.class, target, "layer"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("At least one DispatchExtension is required");
        }

        @Test
        void empty_array_also_fails_immediately() {
            // Given
            GreetingService target = new SimpleGreeter();

            // When / Then
            assertThatThrownBy(() ->
                    ProxyWrapper.createProxy(GreetingService.class, target, "layer",
                            new DispatchExtension[0]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("At least one DispatchExtension is required");
        }
    }

    @Nested
    class When_a_null_extension_is_provided {

        @Test
        void creation_fails_with_the_index_of_the_null_entry() {
            // Given
            GreetingService target = new SimpleGreeter();

            // When / Then
            assertThatThrownBy(() ->
                    ProxyWrapper.createProxy(GreetingService.class, target, "layer",
                            catchAllExtension(), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid extension chain: Catch-all extension SyncDispatchExtension")
                    .hasMessageContaining("found at index 0");
        }
    }

    @Nested
    class When_no_catch_all_extension_is_present {

        @Test
        void creation_fails_with_a_message_suggesting_SyncDispatchExtension() {
            // Given
            GreetingService target = new SimpleGreeter();
            SelectiveExtension selective = new SelectiveExtension();

            // When / Then
            assertThatThrownBy(() ->
                    ProxyWrapper.createProxy(GreetingService.class, target, "layer", selective))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No valid catch-all DispatchExtension found at the end of the chain")
                    .hasMessageContaining("SyncDispatchExtension");
        }
    }

    @Nested
    class When_catch_all_is_not_registered_last {

        @Test
        void creation_fails_indicating_the_misplaced_index() {
            // Given
            GreetingService target = new SimpleGreeter();
            SyncDispatchExtension catchAll = catchAllExtension();
            SelectiveExtension selective = new SelectiveExtension();

            // When / Then — catch-all at index 0, selective at index 1
            assertThatThrownBy(() ->
                    ProxyWrapper.createProxy(GreetingService.class, target, "layer",
                            catchAll, selective))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be the last extension")
                    .hasMessageContaining("unreachable");
        }
    }

    @Nested
    class When_extensions_are_correctly_configured {

        @Test
        void creation_succeeds_with_catch_all_as_last_extension() {
            // Given
            GreetingService target = new SimpleGreeter();
            SelectiveExtension selective = new SelectiveExtension();
            SyncDispatchExtension catchAll = catchAllExtension();

            // When
            GreetingService proxy = ProxyWrapper.createProxy(
                    GreetingService.class, target, "layer", selective, catchAll);

            // Then
            assertThat(proxy.greet("World")).isEqualTo("Hello, World");
        }

        @Test
        void creation_succeeds_with_only_a_catch_all() {
            // Given
            GreetingService target = new SimpleGreeter();
            SyncDispatchExtension catchAll = catchAllExtension();

            // When
            GreetingService proxy = ProxyWrapper.createProxy(
                    GreetingService.class, target, "layer", catchAll);

            // Then
            assertThat(proxy.greet("World")).isEqualTo("Hello, World");
        }
    }

    @Nested
    class IsCatchAll_contract {

        @Test
        void SyncDispatchExtension_reports_itself_as_catch_all() {
            // Given
            SyncDispatchExtension ext = catchAllExtension();

            // When / Then
            assertThat(ext.isCatchAll())
                    .as("SyncDispatchExtension must be a catch-all")
                    .isTrue();
        }

        @Test
        void default_isCatchAll_returns_false() {
            // Given — anonymous extension with default isCatchAll
            DispatchExtension ext = new DispatchExtension() {
                @Override
                public boolean canHandle(Method method) {
                    return false;
                }

                @Override
                public Object dispatch(long chainId, long callId,
                                       Method method, Object[] args, Object target) {
                    return null;
                }
            };

            // When / Then
            assertThat(ext.isCatchAll())
                    .as("Default isCatchAll should return false")
                    .isFalse();
        }
    }
}
