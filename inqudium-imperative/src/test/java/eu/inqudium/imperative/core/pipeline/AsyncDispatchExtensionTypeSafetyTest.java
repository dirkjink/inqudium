package eu.inqudium.imperative.core.pipeline;

import eu.inqudium.core.pipeline.DispatchExtension;
import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.ProxyWrapper;
import eu.inqudium.core.pipeline.SyncDispatchExtension;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link AsyncDispatchExtension} validates the return value
 * at the terminal invocation rather than blindly casting, preventing
 * heap pollution and deferred ClassCastExceptions.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AsyncDispatchExtensionTypeSafetyTest {

    // ======================== Test doubles ========================

    /**
     * Service with both async and sync methods.
     */
    public interface MixedService {
        CompletionStage<String> asyncGreet(String name);

        String syncGreet(String name);
    }

    /**
     * Service whose async method signature returns CompletionStage but
     * the implementation returns null at runtime.
     */
    interface NullReturningAsyncService {
        CompletionStage<String> fetchData();
    }

    // ======================== Helpers ========================

    @SuppressWarnings("unchecked")
    private static <T> T createAsyncProxy(Class<T> iface, T target) {
        AsyncLayerAction<Void, Object> asyncAction =
                (chainId, callId, arg, next) -> next.executeAsync(chainId, callId, arg);
        LayerAction<Void, Object> syncAction =
                (chainId, callId, arg, next) -> next.execute(chainId, callId, arg);

        AsyncDispatchExtension asyncExt =
                new AsyncDispatchExtension((AsyncLayerAction<Void, Object>) asyncAction);
        SyncDispatchExtension syncExt =
                new SyncDispatchExtension((LayerAction<Void, Object>) syncAction);

        return ProxyWrapper.createProxy(iface, target, "async-layer", asyncExt, syncExt);
    }

    // ======================== Tests ========================

    @Nested
    class When_async_method_returns_a_valid_CompletionStage {

        @Test
        void the_result_is_correctly_propagated() throws Exception {
            // Given
            MixedService target = new MixedService() {
                @Override
                public CompletionStage<String> asyncGreet(String name) {
                    return CompletableFuture.completedFuture("Hello, " + name + "!");
                }

                @Override
                public String syncGreet(String name) {
                    return "Hello, " + name + "!";
                }
            };
            MixedService proxy = createAsyncProxy(MixedService.class, target);

            // When
            CompletionStage<String> result = proxy.asyncGreet("World");

            // Then
            assertThat(result.toCompletableFuture().get()).isEqualTo("Hello, World!");
        }
    }

    @Nested
    class When_async_method_returns_null {

        @Test
        void an_IllegalStateException_is_thrown_immediately_with_descriptive_message() {
            // Given
            NullReturningAsyncService target = () -> null;
            NullReturningAsyncService proxy = createAsyncProxy(
                    NullReturningAsyncService.class, target);

            // When / Then
            assertThatThrownBy(proxy::fetchData)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("returned null")
                    .hasMessageContaining("fetchData")
                    .hasMessageContaining("CompletionStage");
        }
    }

    @Nested
    class When_async_method_returns_an_incompatible_type {

        @Test
        void the_error_is_raised_at_invocation_time_not_deferred_to_consumers() {
            // Given — a JDK Proxy whose handler returns a String for an async
            //         method. The generated proxy stub contains a checkcast to
            //         CompletionStage, so the JVM itself throws ClassCastException
            //         before our terminal's instanceof check is reached.
            //         Our instanceof guard is defense-in-depth for edge cases
            //         (e.g. direct handler invocation, future JVM changes).
            //         The important contract: the error surfaces immediately at
            //         invocation time, not as deferred heap pollution downstream.
            NullReturningAsyncService brokenTarget =
                    (NullReturningAsyncService) java.lang.reflect.Proxy.newProxyInstance(
                            NullReturningAsyncService.class.getClassLoader(),
                            new Class<?>[]{NullReturningAsyncService.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("fetchData")) {
                                    return "I am not a CompletionStage";
                                }
                                return null;
                            });

            NullReturningAsyncService proxy = createAsyncProxy(
                    NullReturningAsyncService.class, brokenTarget);

            // When / Then — either the JVM's checkcast (ClassCastException) or
            //               our terminal guard (IllegalStateException) catches it
            assertThatThrownBy(proxy::fetchData)
                    .isInstanceOfAny(ClassCastException.class, IllegalStateException.class);
        }
    }

    @Nested
    class When_sync_methods_are_called_on_a_mixed_proxy {

        @Test
        void sync_methods_are_not_affected_by_async_type_checking() {
            // Given
            MixedService target = new MixedService() {
                @Override
                public CompletionStage<String> asyncGreet(String name) {
                    return CompletableFuture.completedFuture("async");
                }

                @Override
                public String syncGreet(String name) {
                    return "sync: " + name;
                }
            };
            MixedService proxy = createAsyncProxy(MixedService.class, target);

            // When
            String result = proxy.syncGreet("World");

            // Then
            assertThat(result).isEqualTo("sync: World");
        }
    }
}
