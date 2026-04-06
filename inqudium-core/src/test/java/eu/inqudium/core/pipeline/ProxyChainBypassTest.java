package eu.inqudium.core.pipeline;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates the proxy-chain bypass (unrolling flaw):
 * When an outer proxy's extension type does not match the inner proxy's
 * extension type, linkInner finds no counterpart, the chain collapses
 * to the terminal, and the inner proxy's extensions are skipped entirely.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ProxyChainBypassTest {

    // ======================== Test doubles ========================

    interface GreetingService {
        String greet(String name);
    }

    interface AsyncGreetingService {
        CompletionStage<String> greetAsync(String name);
    }

    /**
     * A custom extension that is intentionally NOT a SyncDispatchExtension,
     * so that linkInner from SyncDispatchExtension will never find it.
     */
    static class TrackingExtension implements DispatchExtension {

        private final List<String> invocations;
        private final String label;

        TrackingExtension(String label, List<String> invocations) {
            this.label = label;
            this.invocations = invocations;
        }

        @Override
        public boolean canHandle(Method method) {
            return true; // catch-all, just like SyncDispatchExtension
        }

        @Override
        public boolean isCatchAll() {
            return true;
        }

        @Override
        public Object dispatch(long chainId, long callId,
                               Method method, Object[] args, Object realTarget) throws Throwable {
            invocations.add(label);
            try {
                return method.invoke(realTarget, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }

        @Override
        public DispatchExtension linkInner(DispatchExtension[] innerExtensions) {
            // No chaining logic — standalone extension
            return this;
        }
    }

    // ======================== Helpers ========================

    private static GreetingService realTarget() {
        return name -> "Hello, " + name + "!";
    }

    private static List<String> trackingLog() {
        return new ArrayList<>();
    }

    // ======================== Tests ========================

    @Nested
    class When_both_proxies_use_matching_SyncDispatchExtension {

        @Test
        void both_layers_execute_their_action_in_order() {
            // Given
            List<String> log = trackingLog();

            LayerAction<Void, Object> innerAction = (chainId, callId, arg, next) -> {
                log.add("inner");
                return next.execute(chainId, callId, arg);
            };
            LayerAction<Void, Object> outerAction = (chainId, callId, arg, next) -> {
                log.add("outer");
                return next.execute(chainId, callId, arg);
            };

            SyncDispatchExtension innerExt = new SyncDispatchExtension(innerAction);
            SyncDispatchExtension outerExt = new SyncDispatchExtension(outerAction);

            GreetingService inner = ProxyWrapper.createProxy(
                    GreetingService.class, realTarget(), "inner-layer", innerExt);
            GreetingService outer = ProxyWrapper.createProxy(
                    GreetingService.class, inner, "outer-layer", outerExt);

            // When
            String result = outer.greet("World");

            // Then
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(log).containsExactly("outer", "inner");
        }
    }

    @Nested
    class When_inner_proxy_uses_a_different_extension_type {

        @Test
        void inner_extension_is_completely_bypassed() {
            // Given
            List<String> log = trackingLog();

            // Inner proxy uses a custom TrackingExtension (not SyncDispatchExtension)
            TrackingExtension innerExt = new TrackingExtension("inner-tracking", log);
            GreetingService inner = ProxyWrapper.createProxy(
                    GreetingService.class, realTarget(), "inner-layer", innerExt);

            // Outer proxy uses SyncDispatchExtension
            LayerAction<Void, Object> outerAction = (chainId, callId, arg, next) -> {
                log.add("outer-sync");
                return next.execute(chainId, callId, arg);
            };
            SyncDispatchExtension outerExt = new SyncDispatchExtension(outerAction);
            GreetingService outer = ProxyWrapper.createProxy(
                    GreetingService.class, inner, "outer-layer", outerExt);

            // When
            String result = outer.greet("World");

            // Then — the flaw: inner-tracking is never invoked
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(log)
                    .as("Inner TrackingExtension should have been called but is bypassed "
                            + "because SyncDispatchExtension.linkInner only looks for "
                            + "SyncDispatchExtension in the inner extensions array")
                    .containsExactly("outer-sync", "inner-tracking");
            assertThat(log);
        }

        @Test
        void real_target_is_called_directly_skipping_inner_proxy_entirely() {
            // Given
            List<String> callOrder = trackingLog();

            // A real target that logs when it is called
            GreetingService spyTarget = name -> {
                callOrder.add("realTarget");
                return "Hello, " + name + "!";
            };

            TrackingExtension innerExt = new TrackingExtension("inner", callOrder);
            GreetingService inner = ProxyWrapper.createProxy(
                    GreetingService.class, spyTarget, "inner-layer", innerExt);

            LayerAction<Void, Object> outerAction = (chainId, callId, arg, next) -> {
                callOrder.add("outer");
                return next.execute(chainId, callId, arg);
            };
            SyncDispatchExtension outerExt = new SyncDispatchExtension(outerAction);
            GreetingService outer = ProxyWrapper.createProxy(
                    GreetingService.class, inner, "outer-layer", outerExt);

            // When
            outer.greet("World");

            // Then — terminal calls realTarget directly, inner proxy is unrolled
            assertThat(callOrder)
                    .as("Outer action fires, then realTarget directly — inner proxy is gone")
                    .containsExactly("outer", "inner", "realTarget");
        }
    }

    @Nested
    class When_outer_uses_custom_extension_and_inner_uses_SyncDispatchExtension {

        @Test
        void inner_sync_extension_is_bypassed_in_the_reverse_scenario() {
            // Given
            List<String> log = trackingLog();

            // Inner proxy: SyncDispatchExtension
            LayerAction<Void, Object> innerAction = (chainId, callId, arg, next) -> {
                log.add("inner-sync");
                return next.execute(chainId, callId, arg);
            };
            SyncDispatchExtension innerExt = new SyncDispatchExtension(innerAction);
            GreetingService inner = ProxyWrapper.createProxy(
                    GreetingService.class, realTarget(), "inner-layer", innerExt);

            // Outer proxy: TrackingExtension (linkInner is a no-op, ignores inner)
            TrackingExtension outerExt = new TrackingExtension("outer-tracking", log);
            GreetingService outer = ProxyWrapper.createProxy(
                    GreetingService.class, inner, "outer-layer", outerExt);

            // When
            outer.greet("World");

            // Then — TrackingExtension.linkInner returns itself unchanged,
            //         dispatch goes directly to realTarget, inner-sync is skipped
            assertThat(log)
                    .as("Only the outer tracking extension fires; inner sync layer is skipped")
                    .containsExactly("outer-tracking", "inner-sync");
        }
    }

    @Nested
    class When_three_proxies_are_chained_with_mixed_extensions {

        @Test
        void middle_layer_with_different_extension_type_is_silently_dropped() {
            // Given
            List<String> log = trackingLog();

            // Layer 1 (innermost): SyncDispatchExtension
            LayerAction<Void, Object> layer1Action = (chainId, callId, arg, next) -> {
                log.add("layer1-sync");
                return next.execute(chainId, callId, arg);
            };
            GreetingService proxy1 = ProxyWrapper.createProxy(
                    GreetingService.class, realTarget(), "layer1",
                    new SyncDispatchExtension(layer1Action));

            // Layer 2 (middle): TrackingExtension — different type
            TrackingExtension layer2Ext = new TrackingExtension("layer2-tracking", log);
            GreetingService proxy2 = ProxyWrapper.createProxy(
                    GreetingService.class, proxy1, "layer2", layer2Ext);

            // Layer 3 (outermost): SyncDispatchExtension
            LayerAction<Void, Object> layer3Action = (chainId, callId, arg, next) -> {
                log.add("layer3-sync");
                return next.execute(chainId, callId, arg);
            };
            GreetingService proxy3 = ProxyWrapper.createProxy(
                    GreetingService.class, proxy2, "layer3",
                    new SyncDispatchExtension(layer3Action));

            // When
            proxy3.greet("World");

            // Then — layer3 links to layer1 (both SyncDispatchExtension),
            //         layer2 (TrackingExtension) is completely invisible
            assertThat(log)
                    .as("Layer 2 (TrackingExtension) is silently skipped in the chain walk")
                    .containsExactly("layer3-sync", "layer2-tracking", "layer1-sync");
            assertThat(log);
        }
    }

    @Nested
    class RealTarget_resolution_amplifies_the_bypass {

        @Test
        void nested_proxies_always_resolve_to_the_deepest_real_target() {
            // Given
            GreetingService target = realTarget();
            TrackingExtension ext = new TrackingExtension("x", trackingLog());

            GreetingService proxy1 = ProxyWrapper.createProxy(
                    GreetingService.class, target, "p1", ext);
            GreetingService proxy2 = ProxyWrapper.createProxy(
                    GreetingService.class, proxy1, "p2", ext);
            GreetingService proxy3 = ProxyWrapper.createProxy(
                    GreetingService.class, proxy2, "p3", ext);

            // When — extract the handler to inspect realTarget
            AbstractProxyWrapper handler = (AbstractProxyWrapper)
                    Proxy.getInvocationHandler(proxy3);

            // Then — realTarget is always the original, never an intermediate proxy
            assertThat(handler.realTarget())
                    .as("realTarget always points to the deepest non-proxy object, "
                            + "so any bypassed layer cannot intercept calls")
                    .isSameAs(target);
        }
    }
}
