package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.pipeline.Wrapper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Tests for the sync {@link InqProxyFactory} interface in isolation.
 * No async routing, no {@code InqAsyncProxyFactory} — purely sync proxies.
 */
@DisplayName("InqProxyFactory (sync only)")
class InqProxyFactorySyncTest {

  // =========================================================================
  // Test service interfaces and implementations
  // =========================================================================

  interface GreetingService {
    String greet(String name);

    int countLetters(String text);

    void logEvent(String event);
  }

  interface RiskyService {
    String readFile(String path) throws IOException;
  }

  static class RealGreetingService implements GreetingService {
    @Override
    public String greet(String name) {
      return "Hello, " + name + "!";
    }

    @Override
    public int countLetters(String text) {
      return text.length();
    }

    @Override
    public void logEvent(String event) { /* no-op */ }
  }

  // =========================================================================
  // Test Categories
  // =========================================================================

  @Nested
  @DisplayName("Construction")
  class Construction {

    @Test
    @DisplayName("should reject a non-interface class when protecting")
    void should_reject_a_non_interface_class_when_protecting() {
      // Given
      InqProxyFactory factory = InqProxyFactory.of(
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));

      // When / Then
      assertThatThrownBy(() -> factory.protect(String.class, "target"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("interface");
    }

    @Test
    @DisplayName("should create a factory with a named layer")
    void should_create_a_factory_with_a_named_layer() {
      // Given / When
      InqProxyFactory factory = InqProxyFactory.of("my-layer",
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));
      GreetingService proxy = factory.protect(GreetingService.class, new RealGreetingService());

      // Then
      Assertions.assertThat(((Wrapper<?>) proxy).layerDescription()).isEqualTo("my-layer");
    }

    @Test
    @DisplayName("should assign a default layer name when none is provided")
    void should_assign_a_default_layer_name_when_none_is_provided() {
      // Given / When
      InqProxyFactory factory = InqProxyFactory.of(
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));
      GreetingService proxy = factory.protect(GreetingService.class, new RealGreetingService());

      // Then
      assertThat(((Wrapper<?>) proxy).layerDescription()).isEqualTo("proxy");
    }
  }

  @Nested
  @DisplayName("Method Dispatch")
  class MethodDispatch {

    @Test
    @DisplayName("should proxy a method call and return the correct result")
    void should_proxy_a_method_call_and_return_the_correct_result() {
      // Given
      InqProxyFactory factory = InqProxyFactory.of(
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));
      GreetingService proxy = factory.protect(GreetingService.class, new RealGreetingService());

      // When / Then
      assertThat(proxy.greet("World")).isEqualTo("Hello, World!");
      assertThat(proxy.countLetters("test")).isEqualTo(4);
    }

    @Test
    @DisplayName("should proxy void methods without errors")
    void should_proxy_void_methods_without_errors() {
      // Given
      AtomicBoolean intercepted = new AtomicBoolean(false);
      InqProxyFactory factory = InqProxyFactory.of(
          (chainId, callId, arg, next) -> {
            intercepted.set(true);
            return next.execute(chainId, callId, arg);
          });
      GreetingService proxy = factory.protect(GreetingService.class, new RealGreetingService());

      // When
      proxy.logEvent("test-event");

      // Then
      assertThat(intercepted).isTrue();
    }

    @Test
    @DisplayName("should intercept every method call independently")
    void should_intercept_every_method_call_independently() {
      // Given
      AtomicInteger callCount = new AtomicInteger();
      InqProxyFactory factory = InqProxyFactory.of(
          (chainId, callId, arg, next) -> {
            callCount.incrementAndGet();
            return next.execute(chainId, callId, arg);
          });
      GreetingService proxy = factory.protect(GreetingService.class, new RealGreetingService());

      // When
      proxy.greet("Alice");
      proxy.countLetters("abc");
      proxy.logEvent("x");

      // Then
      assertThat(callCount.get()).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("Around Semantics")
  class AroundSemantics {

    @Test
    @DisplayName("should execute pre-processing before and post-processing after the call")
    void should_execute_pre_and_post_processing_around_the_call() {
      // Given
      List<String> events = Collections.synchronizedList(new ArrayList<>());
      InqProxyFactory factory = InqProxyFactory.of(
          (chainId, callId, arg, next) -> {
            events.add("before");
            Object result = next.execute(chainId, callId, arg);
            events.add("after");
            return result;
          });
      GreetingService proxy = factory.protect(GreetingService.class, new RealGreetingService());

      // When
      String result = proxy.greet("World");

      // Then
      assertThat(result).isEqualTo("Hello, World!");
      assertThat(events).containsExactly("before", "after");
    }

    @Test
    @DisplayName("should allow the around-advice to catch exceptions and return a fallback")
    void should_allow_the_around_advice_to_catch_exceptions_and_return_a_fallback() {
      // Given
      GreetingService failing = new GreetingService() {
        @Override
        public String greet(String name) {
          throw new RuntimeException("fail");
        }

        @Override
        public int countLetters(String text) {
          return 0;
        }

        @Override
        public void logEvent(String event) {
        }
      };

      InqProxyFactory factory = InqProxyFactory.of(
          (chainId, callId, arg, next) -> {
            try {
              return next.execute(chainId, callId, arg);
            } catch (RuntimeException e) {
              return "recovered";
            }
          });
      GreetingService proxy = factory.protect(GreetingService.class, failing);

      // When / Then
      assertThat(proxy.greet("test")).isEqualTo("recovered");
    }

    @Test
    @DisplayName("should allow the around-advice to skip the call entirely")
    void should_allow_the_around_advice_to_skip_the_call_entirely() {
      // Given
      AtomicBoolean targetInvoked = new AtomicBoolean(false);
      GreetingService target = new GreetingService() {
        @Override
        public String greet(String name) {
          targetInvoked.set(true);
          return "real";
        }

        @Override
        public int countLetters(String text) {
          return 0;
        }

        @Override
        public void logEvent(String event) {
        }
      };

      InqProxyFactory factory = InqProxyFactory.of(
          (chainId, callId, arg, next) -> "cached");
      GreetingService proxy = factory.protect(GreetingService.class, target);

      // When
      String result = proxy.greet("test");

      // Then
      assertThat(result).isEqualTo("cached");
      assertThat(targetInvoked).isFalse();
    }
  }

  @Nested
  @DisplayName("Exception Handling")
  class ExceptionHandling {

    @Test
    @DisplayName("should propagate RuntimeException from the target unwrapped")
    void should_propagate_RuntimeException_from_the_target_unwrapped() {
      // Given
      IllegalStateException expected = new IllegalStateException("boom");
      GreetingService failing = new GreetingService() {
        @Override
        public String greet(String name) {
          throw expected;
        }

        @Override
        public int countLetters(String text) {
          return 0;
        }

        @Override
        public void logEvent(String event) {
        }
      };

      InqProxyFactory factory = InqProxyFactory.of(
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));
      GreetingService proxy = factory.protect(GreetingService.class, failing);

      // When / Then
      assertThat(catchThrowable(() -> proxy.greet("test"))).isSameAs(expected);
    }

    @Test
    @DisplayName("should propagate checked exception from the target unwrapped")
    void should_propagate_checked_exception_from_the_target_unwrapped() {
      // Given
      IOException expected = new IOException("disk error");
      RiskyService failing = path -> {
        throw expected;
      };

      InqProxyFactory factory = InqProxyFactory.of(
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));
      RiskyService proxy = factory.protect(RiskyService.class, failing);

      // When / Then
      assertThat(catchThrowable(() -> proxy.readFile("/tmp/missing"))).isSameAs(expected);
    }

    @Test
    @DisplayName("should propagate Error from the target unwrapped")
    void should_propagate_Error_from_the_target_unwrapped() {
      // Given
      OutOfMemoryError expected = new OutOfMemoryError("heap");
      GreetingService failing = new GreetingService() {
        @Override
        public String greet(String name) {
          throw expected;
        }

        @Override
        public int countLetters(String text) {
          return 0;
        }

        @Override
        public void logEvent(String event) {
        }
      };

      InqProxyFactory factory = InqProxyFactory.of(
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));
      GreetingService proxy = factory.protect(GreetingService.class, failing);

      // When / Then
      assertThat(catchThrowable(() -> proxy.greet("test"))).isSameAs(expected);
    }
  }

  @Nested
  @DisplayName("ID Generation")
  class IdGeneration {

    @Test
    @DisplayName("should provide a positive chain id and call id to the around-advice")
    void should_provide_a_positive_chain_id_and_call_id_to_the_around_advice() {
      // Given
      AtomicReference<Long> capturedChainId = new AtomicReference<>();
      AtomicReference<Long> capturedCallId = new AtomicReference<>();
      InqProxyFactory factory = InqProxyFactory.of(
          (chainId, callId, arg, next) -> {
            capturedChainId.set(chainId);
            capturedCallId.set(callId);
            return next.execute(chainId, callId, arg);
          });
      GreetingService proxy = factory.protect(GreetingService.class, new RealGreetingService());

      // When
      proxy.greet("test");

      // Then
      assertThat(capturedChainId.get()).isGreaterThan(0L);
      assertThat(capturedCallId.get()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("should generate different call ids for consecutive invocations")
    void should_generate_different_call_ids_for_consecutive_invocations() {
      // Given
      List<Long> callIds = Collections.synchronizedList(new ArrayList<>());
      InqProxyFactory factory = InqProxyFactory.of(
          (chainId, callId, arg, next) -> {
            callIds.add(callId);
            return next.execute(chainId, callId, arg);
          });
      GreetingService proxy = factory.protect(GreetingService.class, new RealGreetingService());

      // When
      proxy.greet("first");
      proxy.greet("second");

      // Then
      assertThat(callIds).hasSize(2);
      assertThat(callIds.get(0)).isNotEqualTo(callIds.get(1));
    }

    @Test
    @DisplayName("should keep the same chain id across all invocations on the same proxy")
    void should_keep_the_same_chain_id_across_all_invocations_on_the_same_proxy() {
      // Given
      List<Long> chainIds = Collections.synchronizedList(new ArrayList<>());
      InqProxyFactory factory = InqProxyFactory.of(
          (chainId, callId, arg, next) -> {
            chainIds.add(chainId);
            return next.execute(chainId, callId, arg);
          });
      GreetingService proxy = factory.protect(GreetingService.class, new RealGreetingService());

      // When
      proxy.greet("first");
      proxy.greet("second");
      proxy.countLetters("third");

      // Then
      assertThat(chainIds).hasSize(3);
      assertThat(chainIds.stream().distinct().count()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Wrapper Interface")
  class WrapperInterfaceTests {

    @Test
    @DisplayName("should expose a positive chain id through the Wrapper interface")
    void should_expose_a_positive_chain_id_through_the_Wrapper_interface() {
      // Given
      InqProxyFactory factory = InqProxyFactory.of("layer",
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));
      GreetingService proxy = factory.protect(GreetingService.class, new RealGreetingService());

      // When / Then
      assertThat(((Wrapper<?>) proxy).chainId()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("should expose the layer description through the Wrapper interface")
    void should_expose_the_layer_description_through_the_Wrapper_interface() {
      // Given
      InqProxyFactory factory = InqProxyFactory.of("bulkhead",
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));
      GreetingService proxy = factory.protect(GreetingService.class, new RealGreetingService());

      // When / Then
      assertThat(((Wrapper<?>) proxy).layerDescription()).isEqualTo("bulkhead");
    }

    @Test
    @DisplayName("should return null for getInner when wrapping a real target")
    void should_return_null_for_getInner_when_wrapping_a_real_target() {
      // Given
      InqProxyFactory factory = InqProxyFactory.of("layer",
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));
      GreetingService proxy = factory.protect(GreetingService.class, new RealGreetingService());

      // When / Then
      assertThat(((Wrapper<?>) proxy).inner()).isNull();
    }

    @Test
    @DisplayName("should render the layer name in toStringHierarchy")
    void should_render_the_layer_name_in_toStringHierarchy() {
      // Given
      InqProxyFactory factory = InqProxyFactory.of("rate-limiter",
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));
      GreetingService proxy = factory.protect(GreetingService.class, new RealGreetingService());

      // When
      String hierarchy = ((Wrapper<?>) proxy).toStringHierarchy();

      // Then
      assertThat(hierarchy)
          .startsWith("Chain-ID: ")
          .contains("rate-limiter");
    }
  }

  @Nested
  @DisplayName("Chain Stacking")
  class ChainStacking {

    @Test
    @DisplayName("should share the same chain id across nested proxies")
    void should_share_the_same_chain_id_across_nested_proxies() {
      // Given
      List<Long> chainIds = Collections.synchronizedList(new ArrayList<>());
      InqProxyFactory outer = InqProxyFactory.of("retry", (chainId, callId, arg, next) -> {
        chainIds.add(chainId);
        return next.execute(chainId, callId, arg);
      });
      InqProxyFactory inner = InqProxyFactory.of("bulkhead", (chainId, callId, arg, next) -> {
        chainIds.add(chainId);
        return next.execute(chainId, callId, arg);
      });

      GreetingService proxy = outer.protect(GreetingService.class,
          inner.protect(GreetingService.class, new RealGreetingService()));

      // When
      proxy.greet("test");

      // Then
      assertThat(chainIds).hasSize(2);
      assertThat(chainIds.get(0)).isEqualTo(chainIds.get(1));
    }

    @Test
    @DisplayName("should share the same call id across nested proxies")
    void should_share_the_same_call_id_across_nested_proxies() {
      // Given
      List<Long> callIds = Collections.synchronizedList(new ArrayList<>());
      InqProxyFactory outer = InqProxyFactory.of("retry", (chainId, callId, arg, next) -> {
        callIds.add(callId);
        return next.execute(chainId, callId, arg);
      });
      InqProxyFactory inner = InqProxyFactory.of("bulkhead", (chainId, callId, arg, next) -> {
        callIds.add(callId);
        return next.execute(chainId, callId, arg);
      });

      GreetingService proxy = outer.protect(GreetingService.class,
          inner.protect(GreetingService.class, new RealGreetingService()));

      // When
      proxy.greet("test");

      // Then
      assertThat(callIds).hasSize(2);
      assertThat(callIds.get(0)).isEqualTo(callIds.get(1));
    }

    @Test
    @DisplayName("should execute layers in outer-to-inner order")
    void should_execute_layers_in_outer_to_inner_order() {
      // Given
      List<String> events = Collections.synchronizedList(new ArrayList<>());
      InqProxyFactory retry = InqProxyFactory.of("retry", (chainId, callId, arg, next) -> {
        events.add("retry-before");
        Object result = next.execute(chainId, callId, arg);
        events.add("retry-after");
        return result;
      });
      InqProxyFactory bulkhead = InqProxyFactory.of("bulkhead", (chainId, callId, arg, next) -> {
        events.add("bulkhead-before");
        Object result = next.execute(chainId, callId, arg);
        events.add("bulkhead-after");
        return result;
      });

      GreetingService proxy = retry.protect(GreetingService.class,
          bulkhead.protect(GreetingService.class, new RealGreetingService()));

      // When
      String result = proxy.greet("World");

      // Then
      assertThat(result).isEqualTo("Hello, World!");
      assertThat(events).containsExactly(
          "retry-before", "bulkhead-before", "bulkhead-after", "retry-after");
    }

    @Test
    @DisplayName("should stack three layers with correct order and shared ids")
    void should_stack_three_layers_with_correct_order_and_shared_ids() {
      // Given
      List<String> layers = Collections.synchronizedList(new ArrayList<>());
      List<Long> chainIds = Collections.synchronizedList(new ArrayList<>());
      List<Long> callIds = Collections.synchronizedList(new ArrayList<>());

      InqProxyFactory f1 = InqProxyFactory.of("L1", (chainId, callId, arg, next) -> {
        layers.add("L1");
        chainIds.add(chainId);
        callIds.add(callId);
        return next.execute(chainId, callId, arg);
      });
      InqProxyFactory f2 = InqProxyFactory.of("L2", (chainId, callId, arg, next) -> {
        layers.add("L2");
        chainIds.add(chainId);
        callIds.add(callId);
        return next.execute(chainId, callId, arg);
      });
      InqProxyFactory f3 = InqProxyFactory.of("L3", (chainId, callId, arg, next) -> {
        layers.add("L3");
        chainIds.add(chainId);
        callIds.add(callId);
        return next.execute(chainId, callId, arg);
      });

      GreetingService proxy = f1.protect(GreetingService.class,
          f2.protect(GreetingService.class,
              f3.protect(GreetingService.class, new RealGreetingService())));

      // When
      proxy.greet("test");

      // Then
      assertThat(layers).containsExactly("L1", "L2", "L3");
      assertThat(chainIds.stream().distinct().count()).isEqualTo(1);
      assertThat(callIds.stream().distinct().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("should render a multi-layer hierarchy via toStringHierarchy")
    void should_render_a_multi_layer_hierarchy_via_toStringHierarchy() {
      // Given
      InqProxyFactory retry = InqProxyFactory.of("retry",
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));
      InqProxyFactory bulkhead = InqProxyFactory.of("bulkhead",
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));
      InqProxyFactory timeout = InqProxyFactory.of("timeout",
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));

      GreetingService proxy = retry.protect(GreetingService.class,
          bulkhead.protect(GreetingService.class,
              timeout.protect(GreetingService.class, new RealGreetingService())));

      // When
      String hierarchy = ((Wrapper<?>) proxy).toStringHierarchy();

      // Then
      assertThat(hierarchy).contains("retry", "bulkhead", "timeout");
      assertThat(hierarchy.indexOf("retry")).isLessThan(hierarchy.indexOf("bulkhead"));
      assertThat(hierarchy.indexOf("bulkhead")).isLessThan(hierarchy.indexOf("timeout"));
    }

    @Test
    @DisplayName("should return the inner handler via getInner when proxies are nested")
    void should_return_the_inner_handler_via_getInner_when_proxies_are_nested() {
      // Given
      InqProxyFactory outer = InqProxyFactory.of("retry",
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));
      InqProxyFactory inner = InqProxyFactory.of("bulkhead",
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));

      GreetingService proxy = outer.protect(GreetingService.class,
          inner.protect(GreetingService.class, new RealGreetingService()));

      // When
      Wrapper<?> outerWrapper = (Wrapper<?>) proxy;
      Wrapper<?> innerWrapper = outerWrapper.inner();

      // Then
      assertThat(outerWrapper.layerDescription()).isEqualTo("retry");
      assertThat(innerWrapper).isNotNull();
      assertThat(innerWrapper.layerDescription()).isEqualTo("bulkhead");
      assertThat(innerWrapper.inner()).isNull();
    }

    @Test
    @DisplayName("should not flatten non-pipeline proxies")
    void should_not_flatten_non_pipeline_proxies() {
      // Given
      GreetingService manualProxy = (GreetingService) Proxy.newProxyInstance(
          GreetingService.class.getClassLoader(),
          new Class<?>[]{GreetingService.class},
          (proxy, method, args) -> method.invoke(new RealGreetingService(), args)
      );

      AtomicBoolean intercepted = new AtomicBoolean(false);
      InqProxyFactory factory = InqProxyFactory.of("layer",
          (chainId, callId, arg, next) -> {
            intercepted.set(true);
            return next.execute(chainId, callId, arg);
          });

      // When
      GreetingService wrapped = factory.protect(GreetingService.class, manualProxy);
      String result = wrapped.greet("World");

      // Then
      assertThat(result).isEqualTo("Hello, World!");
      assertThat(intercepted).isTrue();
      assertThat(((Wrapper<?>) wrapped).inner()).isNull();
    }

    @Test
    @DisplayName("should generate different call ids across invocations on a stacked proxy")
    void should_generate_different_call_ids_across_invocations_on_a_stacked_proxy() {
      // Given
      List<Long> callIds = Collections.synchronizedList(new ArrayList<>());
      InqProxyFactory outer = InqProxyFactory.of("outer", (chainId, callId, arg, next) -> {
        callIds.add(callId);
        return next.execute(chainId, callId, arg);
      });
      InqProxyFactory inner = InqProxyFactory.of("inner",
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));

      GreetingService proxy = outer.protect(GreetingService.class,
          inner.protect(GreetingService.class, new RealGreetingService()));

      // When
      proxy.greet("first");
      proxy.greet("second");

      // Then
      assertThat(callIds).hasSize(2);
      assertThat(callIds.get(0)).isNotEqualTo(callIds.get(1));
    }
  }

  @Nested
  @DisplayName("Shared Factory")
  class SharedFactory {

    @Test
    @DisplayName("should protect multiple service interfaces with the same factory")
    void should_protect_multiple_service_interfaces_with_the_same_factory() {
      // Given
      AtomicInteger callCount = new AtomicInteger();
      InqProxyFactory factory = InqProxyFactory.of("shared",
          (chainId, callId, arg, next) -> {
            callCount.incrementAndGet();
            return next.execute(chainId, callId, arg);
          });

      GreetingService greetProxy = factory.protect(GreetingService.class, new RealGreetingService());
      RiskyService riskyProxy = factory.protect(RiskyService.class, path -> "content");

      // When
      greetProxy.greet("World");
      try {
        riskyProxy.readFile("/tmp/test");
      } catch (IOException ignored) {
      }

      // Then
      assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("should assign different chain ids to proxies from the same factory")
    void should_assign_different_chain_ids_to_proxies_from_the_same_factory() {
      // Given
      InqProxyFactory factory = InqProxyFactory.of("shared",
          (chainId, callId, arg, next) -> next.execute(chainId, callId, arg));

      GreetingService proxy1 = factory.protect(GreetingService.class, new RealGreetingService());
      GreetingService proxy2 = factory.protect(GreetingService.class, new RealGreetingService());

      // When / Then
      long chainId1 = ((Wrapper<?>) proxy1).chainId();
      long chainId2 = ((Wrapper<?>) proxy2).chainId();
      assertThat(chainId1).isNotEqualTo(chainId2);
    }
  }
}
