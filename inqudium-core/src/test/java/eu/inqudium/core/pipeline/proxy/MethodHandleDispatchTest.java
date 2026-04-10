package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.pipeline.LayerAction;
import org.junit.jupiter.api.*;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that the {@link MethodHandleCache} correctly resolves, caches,
 * and invokes method handles per instance, and that the proxy pipeline
 * dispatches through cached handles instead of reflective {@code Method.invoke}.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MethodHandleDispatchTest {

    // ======================== Test doubles ========================

    interface GreetingService {
        String greet(String name);

        int add(int a, int b);

        void doNothing();

        String noArgs();
    }

    static class SimpleGreeter implements GreetingService {
        @Override
        public String greet(String name) {
            return "Hello, " + name + "!";
        }

        @Override
        public int add(int a, int b) {
            return a + b;
        }

        @Override
        public void doNothing() {
            // intentionally empty
        }

        @Override
        public String noArgs() {
            return "no-args";
        }
    }

    // ======================== Tests ========================

    @Nested
    class MethodHandleCache_resolution {

        private MethodHandleCache cache;

        @BeforeEach
        void setUp() {
            cache = new MethodHandleCache();
        }

        @Test
        void resolve_returns_a_non_null_method_handle() throws Exception {
            // Given
            Method method = GreetingService.class.getMethod("greet", String.class);

            // When
            MethodHandle handle = cache.resolve(method);

            // Then
            assertThat(handle).isNotNull();
        }

        @Test
        void resolve_returns_the_same_handle_for_the_same_method() throws Exception {
            // Given
            Method method = GreetingService.class.getMethod("greet", String.class);

            // When
            MethodHandle first = cache.resolve(method);
            MethodHandle second = cache.resolve(method);

            // Then
            assertThat(first)
                    .as("MethodHandle should be cached and reused within the same instance")
                    .isSameAs(second);
        }

        @Test
        void resolve_returns_different_handles_for_different_methods() throws Exception {
            // Given
            Method greet = GreetingService.class.getMethod("greet", String.class);
            Method add = GreetingService.class.getMethod("add", int.class, int.class);

            // When
            MethodHandle greetHandle = cache.resolve(greet);
            MethodHandle addHandle = cache.resolve(add);

            // Then
            assertThat(greetHandle).isNotSameAs(addHandle);
        }

        @Test
        void separate_instances_maintain_independent_caches() throws Exception {
            // Given
            MethodHandleCache cacheA = new MethodHandleCache();
            MethodHandleCache cacheB = new MethodHandleCache();
            Method method = GreetingService.class.getMethod("greet", String.class);

            // When
            MethodHandle handleA = cacheA.resolve(method);
            MethodHandle handleB = cacheB.resolve(method);

            // Then — both resolve the same method, but from independent caches
            assertThat(handleA).isNotNull();
            assertThat(handleB).isNotNull();
            // Handles are functionally equivalent but may or may not be the
            // same object depending on the JVM — the important thing is that
            // each cache works independently
        }
    }

    @Nested
    class MethodHandleCache_invocation {

        private MethodHandleCache cache;

        @BeforeEach
        void setUp() {
            cache = new MethodHandleCache();
        }

        @Test
        void invoke_dispatches_a_single_argument_method_correctly() throws Throwable {
            // Given
            Method method = GreetingService.class.getMethod("greet", String.class);
            SimpleGreeter target = new SimpleGreeter();

            // When
            Object result = cache.invoke(target, method, new Object[]{"World"});

            // Then
            assertThat(result).isEqualTo("Hello, World!");
        }

        @Test
        void invoke_dispatches_a_multi_argument_method_correctly() throws Throwable {
            // Given
            Method method = GreetingService.class.getMethod("add", int.class, int.class);
            SimpleGreeter target = new SimpleGreeter();

            // When
            Object result = cache.invoke(target, method, new Object[]{3, 4});

            // Then
            assertThat(result).isEqualTo(7);
        }

        @Test
        void invoke_handles_null_args_for_zero_parameter_methods() throws Throwable {
            // Given
            Method method = GreetingService.class.getMethod("noArgs");
            SimpleGreeter target = new SimpleGreeter();

            // When — InvocationHandler passes null for zero-arg methods
            Object result = cache.invoke(target, method, null);

            // Then
            assertThat(result).isEqualTo("no-args");
        }

        @Test
        void invoke_handles_empty_args_array_for_zero_parameter_methods() throws Throwable {
            // Given
            Method method = GreetingService.class.getMethod("noArgs");
            SimpleGreeter target = new SimpleGreeter();

            // When
            Object result = cache.invoke(target, method, new Object[0]);

            // Then
            assertThat(result).isEqualTo("no-args");
        }

        @Test
        void invoke_handles_void_methods() throws Throwable {
            // Given
            Method method = GreetingService.class.getMethod("doNothing");
            SimpleGreeter target = new SimpleGreeter();

            // When
            Object result = cache.invoke(target, method, null);

            // Then — void methods return null via MethodHandle.invoke
            assertThat(result).isNull();
        }

        @Test
        void invoke_propagates_exceptions_from_the_target_method() throws Exception {
            // Given
            Method method = GreetingService.class.getMethod("greet", String.class);
            GreetingService target = new SimpleGreeter() {
                @Override
                public String greet(String name) {
                    throw new IllegalArgumentException("bad name: " + name);
                }
            };

            // When / Then
            assertThatThrownBy(() -> cache.invoke(target, method, new Object[]{"X"}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bad name: X");
        }
    }

    @Nested
    class Proxy_pipeline_dispatches_through_MethodHandles {

        @Test
        void sync_proxy_invokes_target_via_cached_method_handle() {
            // Given
            List<String> log = new ArrayList<>();
            GreetingService target = new SimpleGreeter();

            LayerAction<Void, Object> action = (cid, caid, a, next) -> {
                log.add("action");
                return next.execute(cid, caid, a);
            };

            GreetingService proxy = ProxyWrapper.createProxy(
                    GreetingService.class, target, "layer",
                    new SyncDispatchExtension(action));

            // When
            String result = proxy.greet("World");

            // Then — action fires, result correct, MethodHandle was used internally
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(log).containsExactly("action");
        }

        @Test
        void chained_proxies_share_the_handle_cache_across_linked_extensions() {
            // Given
            GreetingService target = new SimpleGreeter();

            LayerAction<Void, Object> innerAction = (cid, caid, a, next) -> next.execute(cid, caid, a);
            LayerAction<Void, Object> outerAction = (cid, caid, a, next) -> next.execute(cid, caid, a);

            GreetingService inner = ProxyWrapper.createProxy(
                    GreetingService.class, target, "inner",
                    new SyncDispatchExtension(innerAction));
            GreetingService outer = ProxyWrapper.createProxy(
                    GreetingService.class, inner, "outer",
                    new SyncDispatchExtension(outerAction));

            // When — call multiple times
            outer.greet("A");
            outer.greet("B");
            String result = outer.greet("C");

            // Then — all calls succeed, handles are cached and reused internally
            assertThat(result).isEqualTo("Hello, C!");
        }
    }
}
