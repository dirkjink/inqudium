package eu.inqudium.core.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Integration tests demonstrating {@link JoinPointWrapper} with Java dynamic proxies.
 *
 * <p>These tests simulate a realistic AOP scenario: a service interface is proxied
 * via {@link java.lang.reflect.Proxy}, and the proxy's invocation handler wraps
 * each method call in a {@link JoinPointWrapper}. This is conceptually identical
 * to what Spring AOP does with {@code ProceedingJoinPoint::proceed}.</p>
 */
@DisplayName("JoinPointWrapper with Dynamic Proxies")
class JoinPointWrapperDynamicProxyTest {

    // =========================================================================
    // Service contracts and implementations used across all tests
    // =========================================================================

    /**
     * A sample service interface representing a typical business component.
     */
    interface GreetingService {
        String greet(String name);
        int countLetters(String text);
        void validateOrThrow(String input) throws IOException;
    }

    /**
     * A straightforward implementation of the service contract.
     */
    static class DefaultGreetingService implements GreetingService {
        @Override
        public String greet(String name) {
            return "Hello, " + name + "!";
        }

        @Override
        public int countLetters(String text) {
            return text.length();
        }

        @Override
        public void validateOrThrow(String input) throws IOException {
            if (input == null || input.isBlank()) {
                throw new IOException("Input must not be blank");
            }
        }
    }

    // =========================================================================
    // Tracking infrastructure
    // =========================================================================

    /**
     * Captures events from the wrapper chain for assertion in tests.
     */
    static class InterceptionLog {
        final List<String> interceptedMethods = Collections.synchronizedList(new ArrayList<>());
        final List<Long> callIds = Collections.synchronizedList(new ArrayList<>());
        final List<String> layerNames = Collections.synchronizedList(new ArrayList<>());
    }

    /**
     * A {@link JoinPointWrapper} subclass that records each handleLayer invocation,
     * allowing tests to verify that the chain was traversed correctly.
     */
    static class TrackingJoinPointWrapper<R> extends JoinPointWrapper<R> {
        private final InterceptionLog log;

        TrackingJoinPointWrapper(String name, ProxyExecution<R> delegate, InterceptionLog log) {
            super(name, delegate);
            this.log = log;
        }

        @Override
        protected void handleLayer(long callId, Void argument) {
            log.layerNames.add(getLayerDescription());
            log.callIds.add(callId);
        }
    }

    // =========================================================================
    // Proxy factories
    // =========================================================================

    /**
     * Creates a dynamic proxy for the given service that wraps every method
     * invocation in a single {@link JoinPointWrapper} layer — the simplest
     * possible AOP-like interception.
     */
    static GreetingService createSingleLayerProxy(GreetingService target, InterceptionLog log) {
        InvocationHandler handler = (proxy, method, args) -> {
            // Build the wrapper layer name from the method signature, like Spring AOP would
            String layerName = target.getClass().getSimpleName() + "." + method.getName() + "()";

            // Wrap the reflective method invocation as a ProxyExecution
            TrackingJoinPointWrapper<Object> wrapper = new TrackingJoinPointWrapper<>(
                layerName,
                () -> method.invoke(target, args),
                log
            );

            log.interceptedMethods.add(method.getName());
            try {
                return wrapper.proceed();
            } catch (java.lang.reflect.InvocationTargetException e) {
                // method.invoke() wraps target exceptions in InvocationTargetException —
                // unwrap so the proxy sees the original exception type
                throw e.getCause();
            }
        };

        return (GreetingService) Proxy.newProxyInstance(
            GreetingService.class.getClassLoader(),
            new Class<?>[]{ GreetingService.class },
            handler
        );
    }

    /**
     * Creates a dynamic proxy with two chained wrapper layers — an outer "logging"
     * layer and an inner "metrics" layer — demonstrating multi-layer AOP interception.
     */
    static GreetingService createMultiLayerProxy(GreetingService target, InterceptionLog log) {
        InvocationHandler handler = (proxy, method, args) -> {
            String methodSignature = target.getClass().getSimpleName() + "." + method.getName() + "()";

            // Inner layer: closest to the actual method invocation
            TrackingJoinPointWrapper<Object> metricsLayer = new TrackingJoinPointWrapper<>(
                "Metrics[" + methodSignature + "]",
                () -> method.invoke(target, args),
                log
            );

            // Outer layer: wraps around the metrics layer
            TrackingJoinPointWrapper<Object> loggingLayer = new TrackingJoinPointWrapper<>(
                "Logging[" + methodSignature + "]",
                metricsLayer,
                log
            );

            log.interceptedMethods.add(method.getName());

            // Invoke from the outermost layer — the chain propagates inward
            try {
                return loggingLayer.proceed();
            } catch (java.lang.reflect.InvocationTargetException e) {
                // method.invoke() wraps target exceptions in InvocationTargetException —
                // unwrap so the proxy sees the original exception type
                throw e.getCause();
            }
        };

        return (GreetingService) Proxy.newProxyInstance(
            GreetingService.class.getClassLoader(),
            new Class<?>[]{ GreetingService.class },
            handler
        );
    }

    // =========================================================================
    // Test categories
    // =========================================================================

    @Nested
    @DisplayName("Single Layer Proxy")
    class SingleLayerProxy {

        @Test
        @DisplayName("should proxy a method call and return the correct result")
        void should_proxy_a_method_call_and_return_the_correct_result() {
            // Given
            InterceptionLog log = new InterceptionLog();
            GreetingService proxy = createSingleLayerProxy(new DefaultGreetingService(), log);

            // When
            String result = proxy.greet("World");

            // Then
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(log.interceptedMethods).containsExactly("greet");
        }

        @Test
        @DisplayName("should proxy a method returning a primitive and box it correctly")
        void should_proxy_a_method_returning_a_primitive_and_box_it_correctly() {
            // Given
            InterceptionLog log = new InterceptionLog();
            GreetingService proxy = createSingleLayerProxy(new DefaultGreetingService(), log);

            // When
            int count = proxy.countLetters("hello");

            // Then
            assertThat(count).isEqualTo(5);
        }

        @Test
        @DisplayName("should record the method signature as the layer description")
        void should_record_the_method_signature_as_the_layer_description() {
            // Given
            InterceptionLog log = new InterceptionLog();
            GreetingService proxy = createSingleLayerProxy(new DefaultGreetingService(), log);

            // When
            proxy.greet("Alice");

            // Then
            assertThat(log.layerNames)
                .hasSize(1)
                .first()
                .asString()
                .contains("DefaultGreetingService")
                .contains("greet");
        }

        @Test
        @DisplayName("should generate a unique call id for each proxied invocation")
        void should_generate_a_unique_call_id_for_each_proxied_invocation() {
            // Given — a single wrapper instance reused across calls (not recreated per call)
            InterceptionLog log = new InterceptionLog();
            DefaultGreetingService target = new DefaultGreetingService();
            TrackingJoinPointWrapper<Object> wrapper = new TrackingJoinPointWrapper<>(
                "greet()", (ProxyExecution<Object>) () -> target.greet("test"), log
            );

            // When — invoke the same wrapper twice
            try { wrapper.proceed(); } catch (Throwable ignored) {}
            try { wrapper.proceed(); } catch (Throwable ignored) {}

            // Then — the per-instance counter produces distinct IDs for each invocation
            assertThat(log.callIds)
                .hasSize(2);
            assertThat(log.callIds.get(0))
                .isNotEqualTo(log.callIds.get(1));
        }

        @Test
        @DisplayName("should support multiple different methods on the same proxy")
        void should_support_multiple_different_methods_on_the_same_proxy() {
            // Given
            InterceptionLog log = new InterceptionLog();
            GreetingService proxy = createSingleLayerProxy(new DefaultGreetingService(), log);

            // When
            String greeting = proxy.greet("World");
            int count = proxy.countLetters("test");

            // Then
            assertThat(greeting).isEqualTo("Hello, World!");
            assertThat(count).isEqualTo(4);
            assertThat(log.interceptedMethods).containsExactly("greet", "countLetters");
            assertThat(log.layerNames)
                .hasSize(2)
                .anySatisfy(name -> assertThat(name).contains("greet"))
                .anySatisfy(name -> assertThat(name).contains("countLetters"));
        }
    }

    @Nested
    @DisplayName("Multi Layer Proxy")
    class MultiLayerProxy {

        @Test
        @DisplayName("should execute both layers in outer-to-inner order and return the correct result")
        void should_execute_both_layers_in_outer_to_inner_order_and_return_the_correct_result() {
            // Given
            InterceptionLog log = new InterceptionLog();
            GreetingService proxy = createMultiLayerProxy(new DefaultGreetingService(), log);

            // When
            String result = proxy.greet("World");

            // Then
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(log.layerNames)
                .hasSize(2)
                .first().asString().startsWith("Logging");
            assertThat(log.layerNames.get(1)).startsWith("Metrics");
        }

        @Test
        @DisplayName("should pass the same call id through both layers")
        void should_pass_the_same_call_id_through_both_layers() {
            // Given
            InterceptionLog log = new InterceptionLog();
            GreetingService proxy = createMultiLayerProxy(new DefaultGreetingService(), log);

            // When
            proxy.greet("Alice");

            // Then
            assertThat(log.callIds)
                .hasSize(2);
            assertThat(log.callIds.get(0))
                .isEqualTo(log.callIds.get(1));
        }

        @Test
        @DisplayName("should share the same chain id across both layers")
        void should_share_the_same_chain_id_across_both_layers() {
            // Given
            InterceptionLog log = new InterceptionLog();
            DefaultGreetingService target = new DefaultGreetingService();

            // When — manually build the chain to inspect the wrappers
            TrackingJoinPointWrapper<Object> inner = new TrackingJoinPointWrapper<>(
                "inner", () -> target.greet("test"), log
            );
            TrackingJoinPointWrapper<Object> outer = new TrackingJoinPointWrapper<>(
                "outer", inner, log
            );

            // Then
            assertThat(outer.getChainId()).isEqualTo(inner.getChainId());
        }

        @Test
        @DisplayName("should render both proxy layers in the hierarchy visualization")
        void should_render_both_proxy_layers_in_the_hierarchy_visualization() {
            // Given
            InterceptionLog log = new InterceptionLog();
            DefaultGreetingService target = new DefaultGreetingService();

            TrackingJoinPointWrapper<Object> metricsLayer = new TrackingJoinPointWrapper<>(
                "Metrics[greet]", () -> target.greet("test"), log
            );
            TrackingJoinPointWrapper<Object> loggingLayer = new TrackingJoinPointWrapper<>(
                "Logging[greet]", metricsLayer, log
            );

            // When
            String hierarchy = loggingLayer.toStringHierarchy();

            // Then
            assertThat(hierarchy)
                .contains("Chain-ID:")
                .contains("Logging[greet]")
                .contains("Metrics[greet]");
            int loggingIdx = hierarchy.indexOf("Logging[greet]");
            int metricsIdx = hierarchy.indexOf("Metrics[greet]");
            assertThat(loggingIdx).isLessThan(metricsIdx);
        }
    }

    @Nested
    @DisplayName("Exception Handling through Dynamic Proxy")
    class ExceptionHandlingThroughDynamicProxy {

        @Test
        @DisplayName("should propagate a checked exception from the proxied method to the caller")
        void should_propagate_a_checked_exception_from_the_proxied_method_to_the_caller() {
            // Given
            InterceptionLog log = new InterceptionLog();
            GreetingService proxy = createSingleLayerProxy(new DefaultGreetingService(), log);

            // When / Then — the proxy should surface the IOException thrown by validateOrThrow
            assertThatThrownBy(() -> proxy.validateOrThrow(""))
                .isInstanceOf(IOException.class)
                .hasMessage("Input must not be blank");
        }

        @Test
        @DisplayName("should propagate a checked exception through a multi-layer proxy chain")
        void should_propagate_a_checked_exception_through_a_multi_layer_proxy_chain() {
            // Given
            InterceptionLog log = new InterceptionLog();
            GreetingService proxy = createMultiLayerProxy(new DefaultGreetingService(), log);

            // When / Then
            assertThatThrownBy(() -> proxy.validateOrThrow(null))
                .isInstanceOf(IOException.class);
            // Both layers were still visited before the exception was thrown
            assertThat(log.layerNames).hasSize(2);
        }

        @Test
        @DisplayName("should propagate a RuntimeException from the target without wrapping")
        void should_propagate_a_RuntimeException_from_the_target_without_wrapping() {
            // Given — a service that throws an unchecked exception
            GreetingService failingService = new GreetingService() {
                @Override public String greet(String name) {
                    throw new IllegalArgumentException("Name must not be null");
                }
                @Override public int countLetters(String text) { return 0; }
                @Override public void validateOrThrow(String input) {}
            };

            InterceptionLog log = new InterceptionLog();
            GreetingService proxy = createSingleLayerProxy(failingService, log);

            // When
            Throwable thrown = catchThrowable(() -> proxy.greet(null));

            // Then — the original RuntimeException arrives without extra wrapping
            assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Name must not be null");
        }

        @Test
        @DisplayName("should not call the target method when it is void and completes successfully")
        void should_not_call_the_target_method_when_it_is_void_and_completes_successfully()
            throws IOException {
            // Given
            InterceptionLog log = new InterceptionLog();
            GreetingService proxy = createSingleLayerProxy(new DefaultGreetingService(), log);

            // When — valid input should not throw
            proxy.validateOrThrow("valid input");

            // Then
            assertThat(log.interceptedMethods).containsExactly("validateOrThrow");
            assertThat(log.layerNames).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Realistic AOP Simulation")
    class RealisticAopSimulation {

        /**
         * Simulates a Spring-style {@code @Around} advice that measures execution time.
         * The timing layer wraps the actual method call in a JoinPointWrapper and records
         * the elapsed time.
         */
        static GreetingService createTimedProxy(GreetingService target, List<Long> durations) {
            InvocationHandler handler = (proxy, method, args) -> {
                String signature = method.getName() + "()";

                JoinPointWrapper<Object> wrapper = new JoinPointWrapper<>(signature, () -> {
                    // Simulate some work
                    Thread.sleep(10);
                    return method.invoke(target, args);
                });

                long start = System.nanoTime();
                Object result = wrapper.proceed();
                long elapsed = System.nanoTime() - start;
                durations.add(elapsed);

                return result;
            };

            return (GreetingService) Proxy.newProxyInstance(
                GreetingService.class.getClassLoader(),
                new Class<?>[]{ GreetingService.class },
                handler
            );
        }

        @Test
        @DisplayName("should measure execution time of the proxied method like a real AOP aspect")
        void should_measure_execution_time_of_the_proxied_method_like_a_real_AOP_aspect() {
            // Given
            List<Long> durations = Collections.synchronizedList(new ArrayList<>());
            GreetingService proxy = createTimedProxy(new DefaultGreetingService(), durations);

            // When
            String result = proxy.greet("Benchmark");

            // Then
            assertThat(result).isEqualTo("Hello, Benchmark!");
            assertThat(durations)
                .hasSize(1)
                .first()
                .satisfies(d -> assertThat((long) d).isGreaterThan(0));
        }

        @Test
        @DisplayName("should accumulate timing data across multiple proxied calls")
        void should_accumulate_timing_data_across_multiple_proxied_calls() {
            // Given
            List<Long> durations = Collections.synchronizedList(new ArrayList<>());
            GreetingService proxy = createTimedProxy(new DefaultGreetingService(), durations);

            // When
            proxy.greet("First");
            proxy.greet("Second");
            proxy.countLetters("Third");

            // Then
            assertThat(durations).hasSize(3);
            assertThat(durations).allSatisfy(d -> assertThat(d).isGreaterThan(0));
        }

        /**
         * Simulates a conditional caching proxy: the first call to greet() executes
         * the target; subsequent calls with the same argument return the cached result.
         */
        @Test
        @DisplayName("should support a caching proxy that skips the target on cache hit")
        void should_support_a_caching_proxy_that_skips_the_target_on_cache_hit() {
            // Given
            List<String> targetInvocations = Collections.synchronizedList(new ArrayList<>());
            GreetingService trackingTarget = new GreetingService() {
                @Override public String greet(String name) {
                    targetInvocations.add(name);
                    return "Hello, " + name + "!";
                }
                @Override public int countLetters(String text) { return text.length(); }
                @Override public void validateOrThrow(String input) {}
            };

            // A simple cache map shared across invocations
            var cache = Collections.synchronizedMap(new java.util.HashMap<String, Object>());

            InvocationHandler cachingHandler = (proxy, method, args) -> {
                if (method.getName().equals("greet")) {
                    String key = method.getName() + ":" + args[0];
                    if (cache.containsKey(key)) {
                        return cache.get(key);
                    }

                    // Cache miss — execute through the wrapper chain
                    JoinPointWrapper<Object> wrapper = new JoinPointWrapper<>(
                        "Cache[" + method.getName() + "]",
                        () -> method.invoke(trackingTarget, args)
                    );
                    Object result = wrapper.proceed();
                    cache.put(key, result);
                    return result;
                }
                return method.invoke(trackingTarget, args);
            };

            GreetingService proxy = (GreetingService) Proxy.newProxyInstance(
                GreetingService.class.getClassLoader(),
                new Class<?>[]{ GreetingService.class },
                cachingHandler
            );

            // When — call greet("Alice") twice
            String first = proxy.greet("Alice");
            String second = proxy.greet("Alice");
            String different = proxy.greet("Bob");

            // Then — target was only invoked once for "Alice", once for "Bob"
            assertThat(first).isEqualTo("Hello, Alice!");
            assertThat(second).isEqualTo("Hello, Alice!");
            assertThat(different).isEqualTo("Hello, Bob!");
            assertThat(targetInvocations).containsExactly("Alice", "Bob");
        }
    }
}
