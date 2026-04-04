package eu.inqudium.imperative.core.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Tests for {@link InqProxyFactory} — dynamic proxy creation with sync and async routing.
 */
@DisplayName("InqProxyFactory")
class InqProxyFactoryTest {

    // =========================================================================
    // Test service interfaces
    // =========================================================================

    interface CalculatorService {
        int add(int a, int b);
        String concat(String a, String b);
        void fire(String event);
    }

    interface AsyncService {
        CompletionStage<String> fetchAsync(String key);
        CompletableFuture<Integer> computeAsync(int input);
        String fetchSync(String key);
    }

    interface FailingService {
        String riskyCall() throws IOException;
    }

    // =========================================================================
    // Test implementations
    // =========================================================================

    static class RealCalculator implements CalculatorService {
        @Override public int add(int a, int b) { return a + b; }
        @Override public String concat(String a, String b) { return a + b; }
        @Override public void fire(String event) { /* no-op */ }
    }

    static class RealAsyncService implements AsyncService {
        @Override public CompletionStage<String> fetchAsync(String key) {
            return CompletableFuture.completedFuture("value-" + key);
        }
        @Override public CompletableFuture<Integer> computeAsync(int input) {
            return CompletableFuture.completedFuture(input * 2);
        }
        @Override public String fetchSync(String key) {
            return "sync-" + key;
        }
    }

    // =========================================================================
    // Test Categories
    // =========================================================================

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should reject a non-interface class")
        void should_reject_a_non_interface_class() {
            // Given
            InqProxyFactory factory = InqProxyFactory.fromSync(
                (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));

            // When / Then
            assertThatThrownBy(() -> factory.protect(String.class, "target"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interface");
        }
    }

    @Nested
    @DisplayName("Sync Proxy")
    class SyncProxy {

        @Test
        @DisplayName("should proxy method calls and return correct results")
        void should_proxy_method_calls_and_return_correct_results() {
            // Given
            InqProxyFactory factory = InqProxyFactory.fromSync(
                (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));
            CalculatorService proxy = factory.protect(CalculatorService.class, new RealCalculator());

            // When / Then
            assertThat(proxy.add(3, 4)).isEqualTo(7);
            assertThat(proxy.concat("hello", " world")).isEqualTo("hello world");
        }

        @Test
        @DisplayName("should proxy void methods without errors")
        void should_proxy_void_methods_without_errors() {
            // Given
            AtomicBoolean intercepted = new AtomicBoolean(false);
            InqProxyFactory factory = InqProxyFactory.fromSync(
                (chainId, callId, arg, next) -> {
                    intercepted.set(true);
                    return next.execute(chainId, callId, arg);
                });
            CalculatorService proxy = factory.protect(CalculatorService.class, new RealCalculator());

            // When
            proxy.fire("test-event");

            // Then
            assertThat(intercepted).isTrue();
        }

        @Test
        @DisplayName("should execute around-advice before and after the method call")
        void should_execute_around_advice_before_and_after_the_method_call() {
            // Given
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            InqProxyFactory factory = InqProxyFactory.fromSync(
                (chainId, callId, arg, next) -> {
                    events.add("before");
                    Object result = next.execute(chainId, callId, arg);
                    events.add("after");
                    return result;
                });
            CalculatorService proxy = factory.protect(CalculatorService.class, new RealCalculator());

            // When
            int result = proxy.add(1, 2);

            // Then
            assertThat(result).isEqualTo(3);
            assertThat(events).containsExactly("before", "after");
        }

        @Test
        @DisplayName("should intercept every method call independently")
        void should_intercept_every_method_call_independently() {
            // Given
            AtomicInteger callCount = new AtomicInteger();
            InqProxyFactory factory = InqProxyFactory.fromSync(
                (chainId, callId, arg, next) -> {
                    callCount.incrementAndGet();
                    return next.execute(chainId, callId, arg);
                });
            CalculatorService proxy = factory.protect(CalculatorService.class, new RealCalculator());

            // When
            proxy.add(1, 2);
            proxy.concat("a", "b");
            proxy.fire("event");

            // Then
            assertThat(callCount.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("should allow the around-advice to catch exceptions and return a fallback")
        void should_allow_the_around_advice_to_catch_exceptions_and_return_a_fallback() {
            // Given
            CalculatorService failing = new CalculatorService() {
                @Override public int add(int a, int b) { throw new RuntimeException("fail"); }
                @Override public String concat(String a, String b) { return a + b; }
                @Override public void fire(String event) {}
            };

            InqProxyFactory factory = InqProxyFactory.fromSync(
                (chainId, callId, arg, next) -> {
                    try {
                        return next.execute(chainId, callId, arg);
                    } catch (RuntimeException e) {
                        return -1;  // fallback
                    }
                });
            CalculatorService proxy = factory.protect(CalculatorService.class, failing);

            // When / Then
            assertThat(proxy.add(1, 2)).isEqualTo(-1);
        }

        @Test
        @DisplayName("should propagate RuntimeException from the target method")
        void should_propagate_RuntimeException_from_the_target_method() {
            // Given
            IllegalStateException expected = new IllegalStateException("boom");
            CalculatorService failing = new CalculatorService() {
                @Override public int add(int a, int b) { throw expected; }
                @Override public String concat(String a, String b) { return ""; }
                @Override public void fire(String event) {}
            };

            InqProxyFactory factory = InqProxyFactory.fromSync(
                (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));
            CalculatorService proxy = factory.protect(CalculatorService.class, failing);

            // When / Then
            assertThat(catchThrowable(() -> proxy.add(1, 2))).isSameAs(expected);
        }

        @Test
        @DisplayName("should propagate checked exception from the target method unwrapped")
        void should_propagate_checked_exception_from_the_target_method_unwrapped() {
            // Given
            IOException expected = new IOException("disk error");
            FailingService failing = () -> { throw expected; };

            InqProxyFactory factory = InqProxyFactory.fromSync(
                (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));
            FailingService proxy = factory.protect(FailingService.class, failing);

            // When / Then
            assertThat(catchThrowable(proxy::riskyCall)).isSameAs(expected);
        }

        @Test
        @DisplayName("should provide unique call ids for each invocation")
        void should_provide_unique_call_ids_for_each_invocation() {
            // Given
            List<Long> callIds = Collections.synchronizedList(new ArrayList<>());
            InqProxyFactory factory = InqProxyFactory.fromSync(
                (chainId, callId, arg, next) -> {
                    callIds.add(callId);
                    return next.execute(chainId, callId, arg);
                });
            CalculatorService proxy = factory.protect(CalculatorService.class, new RealCalculator());

            // When
            proxy.add(1, 2);
            proxy.add(3, 4);

            // Then
            assertThat(callIds).hasSize(2);
            assertThat(callIds.get(0)).isNotEqualTo(callIds.get(1));
        }
    }

    @Nested
    @DisplayName("Async Routing")
    class AsyncRouting {

        @Test
        @DisplayName("should route CompletionStage methods through the async decorator")
        void should_route_CompletionStage_methods_through_the_async_decorator() {
            // Given
            AtomicBoolean asyncPathUsed = new AtomicBoolean(false);
            AtomicBoolean syncPathUsed = new AtomicBoolean(false);

            InqProxyFactory factory = InqProxyFactory.from(
                // Sync path
                (chainId, callId, arg, next) -> {
                    syncPathUsed.set(true);
                    return next.execute(chainId, callId, arg);
                },
                // Async path
                (chainId, callId, arg, next) -> {
                    asyncPathUsed.set(true);
                    return next.executeAsync(chainId, callId, arg);
                }
            );
            AsyncService proxy = factory.protect(AsyncService.class, new RealAsyncService());

            // When
            proxy.fetchAsync("key");

            // Then
            assertThat(asyncPathUsed).isTrue();
            assertThat(syncPathUsed).isFalse();
        }

        @Test
        @DisplayName("should route non-CompletionStage methods through the sync decorator")
        void should_route_non_CompletionStage_methods_through_the_sync_decorator() {
            // Given
            AtomicBoolean asyncPathUsed = new AtomicBoolean(false);
            AtomicBoolean syncPathUsed = new AtomicBoolean(false);

            InqProxyFactory factory = InqProxyFactory.from(
                (chainId, callId, arg, next) -> {
                    syncPathUsed.set(true);
                    return next.execute(chainId, callId, arg);
                },
                (chainId, callId, arg, next) -> {
                    asyncPathUsed.set(true);
                    return next.executeAsync(chainId, callId, arg);
                }
            );
            AsyncService proxy = factory.protect(AsyncService.class, new RealAsyncService());

            // When
            proxy.fetchSync("key");

            // Then
            assertThat(syncPathUsed).isTrue();
            assertThat(asyncPathUsed).isFalse();
        }

        @Test
        @DisplayName("should route CompletableFuture methods through the async decorator")
        void should_route_CompletableFuture_methods_through_the_async_decorator() {
            // Given — CompletableFuture is a subtype of CompletionStage
            AtomicBoolean asyncPathUsed = new AtomicBoolean(false);

            InqProxyFactory factory = InqProxyFactory.from(
                (chainId, callId, arg, next) -> next.execute(chainId, callId, arg),
                (chainId, callId, arg, next) -> {
                    asyncPathUsed.set(true);
                    return next.executeAsync(chainId, callId, arg);
                }
            );
            AsyncService proxy = factory.protect(AsyncService.class, new RealAsyncService());

            // When
            CompletableFuture<Integer> result = proxy.computeAsync(5);

            // Then
            assertThat(asyncPathUsed).isTrue();
            assertThat(result.join()).isEqualTo(10);
        }

        @Test
        @DisplayName("should return the correct async result through the decorator")
        void should_return_the_correct_async_result_through_the_decorator() {
            // Given
            InqProxyFactory factory = InqProxyFactory.from(
                (chainId, callId, arg, next) -> next.execute(chainId, callId, arg),
                (chainId, callId, arg, next) -> next.executeAsync(chainId, callId, arg)
            );
            AsyncService proxy = factory.protect(AsyncService.class, new RealAsyncService());

            // When
            CompletionStage<String> result = proxy.fetchAsync("test");

            // Then
            assertThat(((CompletableFuture<String>) result).join()).isEqualTo("value-test");
        }

        @Test
        @DisplayName("should allow async around-advice to attach cleanup via whenComplete")
        void should_allow_async_around_advice_to_attach_cleanup_via_whenComplete() {
            // Given
            AtomicBoolean released = new AtomicBoolean(false);

            InqProxyFactory factory = InqProxyFactory.from(
                (chainId, callId, arg, next) -> next.execute(chainId, callId, arg),
                (chainId, callId, arg, next) -> {
                    return next.executeAsync(chainId, callId, arg)
                        .whenComplete((r, e) -> released.set(true));
                }
            );
            AsyncService proxy = factory.protect(AsyncService.class, new RealAsyncService());

            // When
            CompletionStage<String> stage = proxy.fetchAsync("key");
            ((CompletableFuture<String>) stage).join();

            // Then
            assertThat(released).isTrue();
        }

        @Test
        @DisplayName("should handle async failure with cleanup in whenComplete")
        void should_handle_async_failure_with_cleanup_in_whenComplete() {
            // Given
            AtomicBoolean released = new AtomicBoolean(false);

            AsyncService failingAsync = new AsyncService() {
                @Override public CompletionStage<String> fetchAsync(String key) {
                    return CompletableFuture.failedFuture(new RuntimeException("async-fail"));
                }
                @Override public CompletableFuture<Integer> computeAsync(int input) { return null; }
                @Override public String fetchSync(String key) { return ""; }
            };

            InqProxyFactory factory = InqProxyFactory.from(
                (chainId, callId, arg, next) -> next.execute(chainId, callId, arg),
                (chainId, callId, arg, next) -> {
                    return next.executeAsync(chainId, callId, arg)
                        .whenComplete((r, e) -> released.set(true));
                }
            );
            AsyncService proxy = factory.protect(AsyncService.class, failingAsync);

            // When
            CompletionStage<String> stage = proxy.fetchAsync("key");
            catchThrowable(() -> ((CompletableFuture<String>) stage).join());

            // Then
            assertThat(released).isTrue();
        }
    }

    @Nested
    @DisplayName("Shared Proxy Factory")
    class SharedProxyFactory {

        @Test
        @DisplayName("should protect multiple services with the same factory")
        void should_protect_multiple_services_with_the_same_factory() {
            // Given
            AtomicInteger callCount = new AtomicInteger();
            InqProxyFactory factory = InqProxyFactory.fromSync(
                (chainId, callId, arg, next) -> {
                    callCount.incrementAndGet();
                    return next.execute(chainId, callId, arg);
                });

            CalculatorService calcProxy = factory.protect(CalculatorService.class, new RealCalculator());
            AsyncService asyncProxy = factory.protect(AsyncService.class, new RealAsyncService());

            // When
            calcProxy.add(1, 2);
            asyncProxy.fetchSync("key");

            // Then — both go through the same around-advice
            assertThat(callCount.get()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Bulkhead Simulation")
    class BulkheadSimulation {

        @Test
        @DisplayName("should simulate bulkhead acquire and release around a sync proxy call")
        void should_simulate_bulkhead_acquire_and_release_around_a_sync_proxy_call() {
            // Given
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger permits = new AtomicInteger(1);

            InqProxyFactory factory = InqProxyFactory.fromSync(
                (chainId, callId, arg, next) -> {
                    if (permits.decrementAndGet() < 0) {
                        permits.incrementAndGet();
                        throw new RuntimeException("Bulkhead full");
                    }
                    events.add("acquired");
                    try {
                        return next.execute(chainId, callId, arg);
                    } finally {
                        permits.incrementAndGet();
                        events.add("released");
                    }
                });
            CalculatorService proxy = factory.protect(CalculatorService.class, new RealCalculator());

            // When
            int result = proxy.add(10, 20);

            // Then
            assertThat(result).isEqualTo(30);
            assertThat(events).containsExactly("acquired", "released");
            assertThat(permits.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should simulate bulkhead acquire sync and release async around an async proxy call")
        void should_simulate_bulkhead_acquire_sync_and_release_async_around_an_async_proxy_call() {
            // Given
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger permits = new AtomicInteger(1);

            InqProxyFactory factory = InqProxyFactory.from(
                // Sync path (not used in this test)
                (chainId, callId, arg, next) -> next.execute(chainId, callId, arg),
                // Async path — acquire sync, release async
                (chainId, callId, arg, next) -> {
                    if (permits.decrementAndGet() < 0) {
                        permits.incrementAndGet();
                        throw new RuntimeException("Bulkhead full");
                    }
                    events.add("acquired");
                    CompletionStage<Object> stage;
                    try {
                        stage = next.executeAsync(chainId, callId, arg);
                    } catch (Throwable t) {
                        permits.incrementAndGet();
                        events.add("released-on-sync-failure");
                        throw t;
                    }
                    return stage.whenComplete((r, e) -> {
                        permits.incrementAndGet();
                        events.add("released-on-completion");
                    });
                }
            );
            AsyncService proxy = factory.protect(AsyncService.class, new RealAsyncService());

            // When
            CompletionStage<String> stage = proxy.fetchAsync("key");
            String result = ((CompletableFuture<String>) stage).join();

            // Then
            assertThat(result).isEqualTo("value-key");
            assertThat(events).containsExactly("acquired", "released-on-completion");
            assertThat(permits.get()).isEqualTo(1);
        }
    }
}
