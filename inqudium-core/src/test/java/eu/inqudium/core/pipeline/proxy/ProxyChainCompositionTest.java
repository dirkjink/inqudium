package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.pipeline.LayerAction;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies correct proxy-chain composition after the bypass fix.
 * <p>
 * The fix ensures that when an outer proxy's extension type does not match
 * the inner proxy's extension type, calls still pass through the inner proxy
 * instead of jumping directly to the real target.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ProxyChainCompositionTest {

    // ======================== Test doubles ========================

    private static GreetingService realTarget() {
        return name -> "Hello, " + name + "!";
    }

    private static GreetingService spyTarget(List<String> log) {
        return name -> {
            log.add("realTarget");
            return "Hello, " + name + "!";
        };
    }

    // ======================== Helpers ========================

    private static List<String> log() {
        return new ArrayList<>();
    }

    interface GreetingService {
        String greet(String name);
    }

    /**
     * A custom extension intentionally NOT a SyncDispatchExtension,
     * used to test heterogeneous extension composition.
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
            return true;
        }

        @Override
        public boolean isCatchAll() {
            return true;
        }

        @Override
        public Object dispatch(long chainId, long callId,
                               Method method, Object[] args, Object target) throws Throwable {
            invocations.add(label);
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
    }

    // ======================== Tests ========================

    @Nested
    class When_both_proxies_use_matching_SyncDispatchExtension {

        @Test
        void both_layers_execute_their_action_in_correct_order() {
            // Given
            List<String> log = log();

            LayerAction<Void, Object> innerAction = (chainId, callId, arg, next) -> {
                log.add("inner");
                return next.execute(chainId, callId, arg);
            };
            LayerAction<Void, Object> outerAction = (chainId, callId, arg, next) -> {
                log.add("outer");
                return next.execute(chainId, callId, arg);
            };

            GreetingService inner = ProxyWrapper.createProxy(
                    GreetingService.class, realTarget(), "inner-layer",
                    new SyncDispatchExtension(innerAction));
            GreetingService outer = ProxyWrapper.createProxy(
                    GreetingService.class, inner, "outer-layer",
                    new SyncDispatchExtension(outerAction));

            // When
            String result = outer.greet("World");

            // Then
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(log).containsExactly("outer", "inner");
        }

        @Test
        void linked_chain_walk_invokes_real_target_exactly_once() {
            // Given
            List<String> log = log();
            GreetingService spy = spyTarget(log);

            LayerAction<Void, Object> noopAction = (cid, caid, a, next) -> next.execute(cid, caid, a);

            GreetingService inner = ProxyWrapper.createProxy(
                    GreetingService.class, spy, "inner",
                    new SyncDispatchExtension(noopAction));
            GreetingService outer = ProxyWrapper.createProxy(
                    GreetingService.class, inner, "outer",
                    new SyncDispatchExtension(noopAction));

            // When
            outer.greet("World");

            // Then — real target is called exactly once (no double dispatch)
            assertThat(log).containsExactly("realTarget");
        }
    }

    @Nested
    class When_inner_proxy_uses_a_different_extension_type {

        @Test
        void inner_extension_is_correctly_invoked_through_proxy_dispatch() {
            // Given
            List<String> log = log();

            // Inner proxy: TrackingExtension (not a SyncDispatchExtension)
            TrackingExtension innerExt = new TrackingExtension("inner-tracking", log);
            GreetingService inner = ProxyWrapper.createProxy(
                    GreetingService.class, realTarget(), "inner-layer", innerExt);

            // Outer proxy: SyncDispatchExtension
            LayerAction<Void, Object> outerAction = (chainId, callId, arg, next) -> {
                log.add("outer-sync");
                return next.execute(chainId, callId, arg);
            };
            GreetingService outer = ProxyWrapper.createProxy(
                    GreetingService.class, inner, "outer-layer",
                    new SyncDispatchExtension(outerAction));

            // When
            String result = outer.greet("World");

            // Then — both extensions fire in correct order
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(log)
                    .as("Outer sync action and inner tracking extension both execute")
                    .containsExactly("outer-sync", "inner-tracking");
        }

        @Test
        void call_passes_through_inner_proxy_not_directly_to_real_target() {
            // Given
            List<String> log = log();
            GreetingService spy = spyTarget(log);

            TrackingExtension innerExt = new TrackingExtension("inner", log);
            GreetingService inner = ProxyWrapper.createProxy(
                    GreetingService.class, spy, "inner-layer", innerExt);

            LayerAction<Void, Object> outerAction = (chainId, callId, arg, next) -> {
                log.add("outer");
                return next.execute(chainId, callId, arg);
            };
            GreetingService outer = ProxyWrapper.createProxy(
                    GreetingService.class, inner, "outer-layer",
                    new SyncDispatchExtension(outerAction));

            // When
            outer.greet("World");

            // Then — outer fires, then inner proxy intercepts, then real target
            assertThat(log).containsExactly("outer", "inner", "realTarget");
        }
    }

    @Nested
    class When_outer_uses_custom_extension_and_inner_uses_SyncDispatchExtension {

        @Test
        void inner_sync_extension_is_correctly_invoked() {
            // Given
            List<String> log = log();

            // Inner: SyncDispatchExtension
            LayerAction<Void, Object> innerAction = (chainId, callId, arg, next) -> {
                log.add("inner-sync");
                return next.execute(chainId, callId, arg);
            };
            GreetingService inner = ProxyWrapper.createProxy(
                    GreetingService.class, realTarget(), "inner-layer",
                    new SyncDispatchExtension(innerAction));

            // Outer: TrackingExtension (dispatches through proxy target)
            TrackingExtension outerExt = new TrackingExtension("outer-tracking", log);
            GreetingService outer = ProxyWrapper.createProxy(
                    GreetingService.class, inner, "outer-layer", outerExt);

            // When
            outer.greet("World");

            // Then — both extensions fire
            assertThat(log)
                    .as("Outer tracking and inner sync both execute in order")
                    .containsExactly("outer-tracking", "inner-sync");
        }
    }

    @Nested
    class When_three_proxies_are_chained_with_mixed_extensions {

        @Test
        void all_three_layers_execute_in_correct_order() {
            // Given
            List<String> log = log();

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

            // Then — all three layers fire, none are silently dropped
            assertThat(log)
                    .as("All three layers execute: outer sync → middle tracking → inner sync")
                    .containsExactly("layer3-sync", "layer2-tracking", "layer1-sync");
        }

        @Test
        void real_target_is_invoked_exactly_once_at_the_end_of_the_chain() {
            // Given
            List<String> log = log();
            GreetingService spy = spyTarget(log);

            LayerAction<Void, Object> noopAction = (cid, caid, a, next) -> {
                log.add("sync-layer");
                return next.execute(cid, caid, a);
            };

            GreetingService proxy1 = ProxyWrapper.createProxy(
                    GreetingService.class, spy, "layer1",
                    new SyncDispatchExtension(noopAction));

            TrackingExtension trackingExt = new TrackingExtension("tracking-layer", log);
            GreetingService proxy2 = ProxyWrapper.createProxy(
                    GreetingService.class, proxy1, "layer2", trackingExt);

            GreetingService proxy3 = ProxyWrapper.createProxy(
                    GreetingService.class, proxy2, "layer3",
                    new SyncDispatchExtension(noopAction));

            // When
            String result = proxy3.greet("World");

            // Then
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(log).containsExactly(
                    "sync-layer", "tracking-layer", "sync-layer", "realTarget");
        }
    }

    @Nested
    class When_four_proxies_alternate_extension_types {

        @Test
        void all_four_layers_execute_without_any_bypass() {
            // Given
            List<String> log = log();

            LayerAction<Void, Object> syncAction1 = (cid, caid, a, next) -> {
                log.add("sync-1");
                return next.execute(cid, caid, a);
            };
            LayerAction<Void, Object> syncAction2 = (cid, caid, a, next) -> {
                log.add("sync-2");
                return next.execute(cid, caid, a);
            };

            // Sync → Tracking → Sync → Tracking
            GreetingService p1 = ProxyWrapper.createProxy(
                    GreetingService.class, realTarget(), "p1",
                    new TrackingExtension("tracking-1", log));
            GreetingService p2 = ProxyWrapper.createProxy(
                    GreetingService.class, p1, "p2",
                    new SyncDispatchExtension(syncAction1));
            GreetingService p3 = ProxyWrapper.createProxy(
                    GreetingService.class, p2, "p3",
                    new TrackingExtension("tracking-2", log));
            GreetingService p4 = ProxyWrapper.createProxy(
                    GreetingService.class, p3, "p4",
                    new SyncDispatchExtension(syncAction2));

            // When
            String result = p4.greet("World");

            // Then — all four layers fire in outer-to-inner order
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(log).containsExactly(
                    "sync-2", "tracking-2", "sync-1", "tracking-1");
        }
    }

    @Nested
    class RealTarget_resolution_no_longer_causes_bypass {

        @Test
        void nested_proxies_still_resolve_real_target_to_deepest_object() {
            // Given — realTarget resolution is unchanged (needed for linked optimisation)
            GreetingService target = realTarget();
            TrackingExtension ext = new TrackingExtension("x", log());

            GreetingService proxy1 = ProxyWrapper.createProxy(
                    GreetingService.class, target, "p1", ext);
            GreetingService proxy2 = ProxyWrapper.createProxy(
                    GreetingService.class, proxy1, "p2", ext);

            AbstractProxyWrapper handler = (AbstractProxyWrapper)
                    Proxy.getInvocationHandler(proxy2);

            // Then — realTarget still points to the deepest non-proxy object
            assertThat(handler.realTarget()).isSameAs(target);
        }

        @Test
        void despite_deep_real_target_all_intermediate_extensions_still_fire() {
            // Given
            List<String> log = log();
            GreetingService spy = spyTarget(log);

            TrackingExtension ext1 = new TrackingExtension("layer-1", log);
            TrackingExtension ext2 = new TrackingExtension("layer-2", log);
            TrackingExtension ext3 = new TrackingExtension("layer-3", log);

            GreetingService proxy1 = ProxyWrapper.createProxy(
                    GreetingService.class, spy, "p1", ext1);
            GreetingService proxy2 = ProxyWrapper.createProxy(
                    GreetingService.class, proxy1, "p2", ext2);
            GreetingService proxy3 = ProxyWrapper.createProxy(
                    GreetingService.class, proxy2, "p3", ext3);

            // When
            proxy3.greet("World");

            // Then — every layer fires, real target called once at the end
            assertThat(log).containsExactly(
                    "layer-3", "layer-2", "layer-1", "realTarget");
        }
    }
}
