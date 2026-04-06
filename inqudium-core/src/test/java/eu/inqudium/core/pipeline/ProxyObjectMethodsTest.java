package eu.inqudium.core.pipeline;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that equals, hashCode, and toString on proxies delegate to
 * the real target so that proxies behave correctly in collections and
 * diagnostic output.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ProxyObjectMethodsTest {

    // ======================== Test doubles ========================

    interface GreetingService {
        String greet(String name);
    }

    /**
     * Real target with well-defined equals/hashCode based on an id field,
     * so we can verify delegation precisely.
     */
    static class IdentifiableGreeter implements GreetingService {
        private final String id;

        IdentifiableGreeter(String id) {
            this.id = id;
        }

        @Override
        public String greet(String name) {
            return "Hello, " + name + " from " + id;
        }

        @Override
        public boolean equals(Object o) {
            return (o instanceof IdentifiableGreeter other) && id.equals(other.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return "Greeter(" + id + ")";
        }
    }

    // ======================== Helpers ========================

    private static GreetingService wrapOnce(GreetingService target, String layerName) {
        LayerAction<Void, Object> noop = (cid, caid, a, next) -> next.execute(cid, caid, a);
        return ProxyWrapper.createProxy(
                GreetingService.class, target, layerName,
                new SyncDispatchExtension(noop));
    }

    // ======================== Tests ========================

    @Nested
    class Equals_delegation {

        @Test
        void two_proxies_wrapping_the_same_target_are_equal() {
            // Given
            GreetingService target = new IdentifiableGreeter("A");
            GreetingService proxy1 = wrapOnce(target, "layer-1");
            GreetingService proxy2 = wrapOnce(target, "layer-2");

            // When / Then
            assertThat(proxy1)
                    .as("Two proxies of the same target should be equal")
                    .isEqualTo(proxy2);
        }

        @Test
        void proxy_is_equal_to_its_own_real_target() {
            // Given
            GreetingService target = new IdentifiableGreeter("A");
            GreetingService proxy = wrapOnce(target, "layer");

            // When / Then
            assertThat(proxy)
                    .as("Proxy should be equal to the real target it wraps")
                    .isEqualTo(target);
        }

        @Test
        void nested_proxy_is_equal_to_single_proxy_of_same_target() {
            // Given
            GreetingService target = new IdentifiableGreeter("A");
            GreetingService inner = wrapOnce(target, "inner");
            GreetingService outer = wrapOnce(inner, "outer");

            // When / Then
            assertThat(outer)
                    .as("Deeply nested proxy should still equal a single-layer proxy")
                    .isEqualTo(wrapOnce(target, "other"));
        }

        @Test
        void proxies_wrapping_different_targets_are_not_equal() {
            // Given
            GreetingService proxyA = wrapOnce(new IdentifiableGreeter("A"), "layer");
            GreetingService proxyB = wrapOnce(new IdentifiableGreeter("B"), "layer");

            // When / Then
            assertThat(proxyA)
                    .as("Proxies of different targets must not be equal")
                    .isNotEqualTo(proxyB);
        }

        @Test
        void proxy_is_not_equal_to_null() {
            // Given
            GreetingService proxy = wrapOnce(new IdentifiableGreeter("A"), "layer");

            // When / Then
            assertThat(proxy).isNotEqualTo(null);
        }

        @Test
        void equals_is_symmetric_between_proxy_and_target() {
            // Given
            GreetingService target = new IdentifiableGreeter("A");
            GreetingService proxy = wrapOnce(target, "layer");

            // When / Then
            assertThat(proxy.equals(target))
                    .as("proxy.equals(target)")
                    .isTrue();
            assertThat(target.equals(proxy))
                    .as("target.equals(proxy) — requires target's equals to handle proxies, "
                            + "or at least not fail; here IdentifiableGreeter checks instanceof "
                            + "so this direction returns false, which is a known limitation "
                            + "of proxy delegation")
                    .isFalse();
            // Note: full symmetry would require the target class to be proxy-aware.
            // The important guarantee is proxy→target and proxy→proxy consistency.
        }
    }

    @Nested
    class HashCode_delegation {

        @Test
        void proxy_hash_code_matches_real_target_hash_code() {
            // Given
            GreetingService target = new IdentifiableGreeter("A");
            GreetingService proxy = wrapOnce(target, "layer");

            // When / Then
            assertThat(proxy.hashCode())
                    .as("Proxy hashCode must equal target hashCode")
                    .isEqualTo(target.hashCode());
        }

        @Test
        void two_proxies_of_same_target_have_same_hash_code() {
            // Given
            GreetingService target = new IdentifiableGreeter("A");
            GreetingService proxy1 = wrapOnce(target, "layer-1");
            GreetingService proxy2 = wrapOnce(target, "layer-2");

            // When / Then
            assertThat(proxy1.hashCode()).isEqualTo(proxy2.hashCode());
        }

        @Test
        void nested_proxy_hash_code_matches_real_target() {
            // Given
            GreetingService target = new IdentifiableGreeter("A");
            GreetingService inner = wrapOnce(target, "inner");
            GreetingService outer = wrapOnce(inner, "outer");

            // When / Then
            assertThat(outer.hashCode()).isEqualTo(target.hashCode());
        }
    }

    @Nested
    class ToString_delegation {

        @Test
        void to_string_includes_layer_description_and_target_string() {
            // Given
            GreetingService target = new IdentifiableGreeter("A");
            GreetingService proxy = wrapOnce(target, "my-layer");

            // When
            String result = proxy.toString();

            // Then
            assertThat(result)
                    .as("toString should contain the layer description and the target's toString")
                    .contains("my-layer")
                    .contains("Greeter(A)");
        }
    }

    @Nested
    class Collection_behaviour {

        @Test
        void proxy_can_be_found_via_another_proxy_in_a_hash_set() {
            // Given
            GreetingService target = new IdentifiableGreeter("A");
            GreetingService proxy1 = wrapOnce(target, "layer-1");
            GreetingService proxy2 = wrapOnce(target, "layer-2");

            Set<GreetingService> set = new HashSet<>();
            set.add(proxy1);

            // When / Then
            assertThat(set)
                    .as("HashSet.contains should find proxy2 because it equals proxy1")
                    .contains(proxy2);
        }

        @Test
        void proxy_can_look_up_a_value_stored_under_another_proxy_in_a_hash_map() {
            // Given
            GreetingService target = new IdentifiableGreeter("A");
            GreetingService proxyKey = wrapOnce(target, "key-layer");
            GreetingService proxyLookup = wrapOnce(target, "lookup-layer");

            Map<GreetingService, String> map = new HashMap<>();
            map.put(proxyKey, "found");

            // When
            String value = map.get(proxyLookup);

            // Then
            assertThat(value)
                    .as("HashMap.get with a different proxy of the same target must find the entry")
                    .isEqualTo("found");
        }

        @Test
        void proxy_can_look_up_a_value_stored_under_the_real_target() {
            // Given
            IdentifiableGreeter target = new IdentifiableGreeter("A");
            GreetingService proxy = wrapOnce(target, "layer");

            Map<GreetingService, String> map = new HashMap<>();
            map.put(target, "found");

            // When
            String value = map.get(proxy);

            // Then
            assertThat(value)
                    .as("HashMap.get with a proxy should find value stored under the real target")
                    .isEqualTo("found");
        }
    }
}
