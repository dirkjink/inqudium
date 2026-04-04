package eu.inqudium.core.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Integration tests demonstrating {@link JoinPointWrapper} with Java dynamic proxies
 * and {@link LayerAction} around-advice.
 */
@DisplayName("JoinPointWrapper with Dynamic Proxies")
class JoinPointWrapperDynamicProxyTest {

  // =========================================================================
  // Service contracts and implementations
  // =========================================================================

  /**
   * Creates a {@link LayerAction} that records layer name and call ID for test assertions.
   */
  static <A, R> LayerAction<A, R> trackingAction(String layerName, InterceptionLog log) {
    return (chainId, callId, argument, next) -> {
      log.layerNames.add(layerName);
      log.callIds.add(callId);
      return next.execute(chainId, callId, argument);
    };
  }

  /**
   * Creates a dynamic proxy with a single tracking layer per method invocation.
   */
  static GreetingService createSingleLayerProxy(GreetingService target, InterceptionLog log) {
    InvocationHandler handler = (proxy, method, args) -> {
      String layerName = target.getClass().getSimpleName() + "." + method.getName() + "()";

      JoinPointWrapper<Object> wrapper = new JoinPointWrapper<>(
          layerName,
          () -> method.invoke(target, args),
          trackingAction(layerName, log)
      );

      log.interceptedMethods.add(method.getName());
      try {
        return wrapper.proceed();
      } catch (java.lang.reflect.InvocationTargetException e) {
        throw e.getCause();
      }
    };

    return (GreetingService) Proxy.newProxyInstance(
        GreetingService.class.getClassLoader(),
        new Class<?>[]{GreetingService.class},
        handler
    );
  }

  // =========================================================================
  // Tracking infrastructure
  // =========================================================================

  /**
   * Creates a dynamic proxy with two chained wrapper layers — an outer "logging"
   * layer and an inner "metrics" layer — demonstrating multi-layer AOP interception.
   */
  static GreetingService createMultiLayerProxy(GreetingService target, InterceptionLog log) {
    InvocationHandler handler = (proxy, method, args) -> {
      String methodSignature = target.getClass().getSimpleName() + "." + method.getName() + "()";

      // Inner layer with metrics tracking action
      JoinPointWrapper<Object> metricsLayer = new JoinPointWrapper<>(
          "Metrics[" + methodSignature + "]",
          () -> method.invoke(target, args),
          trackingAction("Metrics[" + methodSignature + "]", log)
      );

      // Outer layer with logging tracking action
      JoinPointWrapper<Object> loggingLayer = new JoinPointWrapper<>(
          "Logging[" + methodSignature + "]",
          metricsLayer,
          trackingAction("Logging[" + methodSignature + "]", log)
      );

      log.interceptedMethods.add(method.getName());
      try {
        return loggingLayer.proceed();
      } catch (java.lang.reflect.InvocationTargetException e) {
        throw e.getCause();
      }
    };

    return (GreetingService) Proxy.newProxyInstance(
        GreetingService.class.getClassLoader(),
        new Class<?>[]{GreetingService.class},
        handler
    );
  }

  interface GreetingService {
    String greet(String name);

    int countLetters(String text);

    void validateOrThrow(String input) throws IOException;
  }

  // =========================================================================
  // Proxy factories
  // =========================================================================

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
      if (input == null || input.isBlank()) throw new IOException("Input must not be blank");
    }
  }

  static class InterceptionLog {
    final List<String> interceptedMethods = Collections.synchronizedList(new ArrayList<>());
    final List<Long> callIds = Collections.synchronizedList(new ArrayList<>());
    final List<String> layerNames = Collections.synchronizedList(new ArrayList<>());
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

      // When / Then
      assertThat(proxy.countLetters("hello")).isEqualTo(5);
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
      assertThat(log.layerNames).hasSize(1).first().asString()
          .contains("DefaultGreetingService").contains("greet");
    }

    @Test
    @DisplayName("should generate a unique call id for each proxied invocation")
    void should_generate_a_unique_call_id_for_each_proxied_invocation() {
      // Given — a single wrapper instance reused across calls
      InterceptionLog log = new InterceptionLog();
      DefaultGreetingService target = new DefaultGreetingService();
      JoinPointWrapper<Object> wrapper = new JoinPointWrapper<>(
          "greet()", (ProxyExecution<Object>) () -> target.greet("test"),
          trackingAction("greet()", log)
      );

      // When
      try {
        wrapper.proceed();
      } catch (Throwable ignored) {
      }
      try {
        wrapper.proceed();
      } catch (Throwable ignored) {
      }

      // Then
      assertThat(log.callIds).hasSize(2);
      assertThat(log.callIds.get(0)).isNotEqualTo(log.callIds.get(1));
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
      assertThat(log.layerNames).hasSize(2);
      assertThat(log.layerNames.get(0)).startsWith("Logging");
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
      assertThat(log.callIds).hasSize(2);
      assertThat(log.callIds.get(0)).isEqualTo(log.callIds.get(1));
    }

    @Test
    @DisplayName("should share the same chain id across both layers")
    void should_share_the_same_chain_id_across_both_layers() {
      // Given
      DefaultGreetingService target = new DefaultGreetingService();
      JoinPointWrapper<Object> inner = new JoinPointWrapper<>("inner", () -> target.greet("test"));
      JoinPointWrapper<Object> outer = new JoinPointWrapper<>("outer", inner);

      // When / Then
      assertThat(outer.getChainId()).isEqualTo(inner.getChainId());
    }

    @Test
    @DisplayName("should render both proxy layers in the hierarchy visualization")
    void should_render_both_proxy_layers_in_the_hierarchy_visualization() {
      // Given
      DefaultGreetingService target = new DefaultGreetingService();
      JoinPointWrapper<Object> metricsLayer = new JoinPointWrapper<>("Metrics[greet]", () -> target.greet("test"));
      JoinPointWrapper<Object> loggingLayer = new JoinPointWrapper<>("Logging[greet]", metricsLayer);

      // When
      String hierarchy = loggingLayer.toStringHierarchy();

      // Then
      assertThat(hierarchy).contains("Logging[greet]").contains("Metrics[greet]");
      assertThat(hierarchy.indexOf("Logging[greet]")).isLessThan(hierarchy.indexOf("Metrics[greet]"));
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

      // When / Then
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
      assertThatThrownBy(() -> proxy.validateOrThrow(null)).isInstanceOf(IOException.class);
      assertThat(log.layerNames).hasSize(2);
    }

    @Test
    @DisplayName("should propagate a RuntimeException from the target without wrapping")
    void should_propagate_a_RuntimeException_from_the_target_without_wrapping() {
      // Given
      GreetingService failingService = new GreetingService() {
        @Override
        public String greet(String name) {
          throw new IllegalArgumentException("Name invalid");
        }

        @Override
        public int countLetters(String text) {
          return 0;
        }

        @Override
        public void validateOrThrow(String input) {
        }
      };
      InterceptionLog log = new InterceptionLog();
      GreetingService proxy = createSingleLayerProxy(failingService, log);

      // When / Then
      assertThat(catchThrowable(() -> proxy.greet(null)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Name invalid");
    }

    @Test
    @DisplayName("should complete successfully for void methods with valid input")
    void should_complete_successfully_for_void_methods_with_valid_input() throws IOException {
      // Given
      InterceptionLog log = new InterceptionLog();
      GreetingService proxy = createSingleLayerProxy(new DefaultGreetingService(), log);

      // When
      proxy.validateOrThrow("valid input");

      // Then
      assertThat(log.interceptedMethods).containsExactly("validateOrThrow");
    }
  }

  @Nested
  @DisplayName("Realistic AOP Simulation")
  class RealisticAopSimulation {

    @Test
    @DisplayName("should measure execution time of the proxied method like a real AOP aspect")
    void should_measure_execution_time_of_the_proxied_method_like_a_real_AOP_aspect() {
      // Given
      List<Long> durations = Collections.synchronizedList(new ArrayList<>());
      DefaultGreetingService target = new DefaultGreetingService();

      // Build a timing proxy using LayerAction
      InvocationHandler handler = (proxy, method, args) -> {
        JoinPointWrapper<Object> wrapper = new JoinPointWrapper<>(
            method.getName() + "()",
            () -> method.invoke(target, args),
            (chainId, callId, arg, next) -> {
              long start = System.nanoTime();
              Object result = next.execute(chainId, callId, arg);
              durations.add(System.nanoTime() - start);
              return result;
            }
        );
        try {
          return wrapper.proceed();
        } catch (java.lang.reflect.InvocationTargetException e) {
          throw e.getCause();
        }
      };

      GreetingService proxy = (GreetingService) Proxy.newProxyInstance(
          GreetingService.class.getClassLoader(),
          new Class<?>[]{GreetingService.class}, handler
      );

      // When
      String result = proxy.greet("Benchmark");

      // Then
      assertThat(result).isEqualTo("Hello, Benchmark!");
      assertThat(durations).hasSize(1).allSatisfy(d -> assertThat(d).isGreaterThan(0));
    }

    @Test
    @DisplayName("should support a caching proxy that skips the target on cache hit")
    void should_support_a_caching_proxy_that_skips_the_target_on_cache_hit() {
      // Given
      List<String> targetInvocations = Collections.synchronizedList(new ArrayList<>());
      GreetingService trackingTarget = new GreetingService() {
        @Override
        public String greet(String name) {
          targetInvocations.add(name);
          return "Hello, " + name + "!";
        }

        @Override
        public int countLetters(String text) {
          return text.length();
        }

        @Override
        public void validateOrThrow(String input) {
        }
      };

      var cache = Collections.synchronizedMap(new java.util.HashMap<String, Object>());

      InvocationHandler cachingHandler = (proxy, method, args) -> {
        if (method.getName().equals("greet")) {
          String key = "greet:" + args[0];
          if (cache.containsKey(key)) return cache.get(key);

          // Cache miss — execute through the wrapper with a caching LayerAction
          JoinPointWrapper<Object> wrapper = new JoinPointWrapper<>(
              "Cache[" + method.getName() + "]",
              () -> method.invoke(trackingTarget, args),
              (chainId, callId, arg, next) -> {
                Object result = next.execute(chainId, callId, arg);
                cache.put(key, result);
                return result;
              }
          );
          try {
            return wrapper.proceed();
          } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
          }
        }
        return method.invoke(trackingTarget, args);
      };

      GreetingService proxy = (GreetingService) Proxy.newProxyInstance(
          GreetingService.class.getClassLoader(),
          new Class<?>[]{GreetingService.class}, cachingHandler
      );

      // When
      String first = proxy.greet("Alice");
      String second = proxy.greet("Alice");
      String different = proxy.greet("Bob");

      // Then
      assertThat(first).isEqualTo("Hello, Alice!");
      assertThat(second).isEqualTo("Hello, Alice!");
      assertThat(different).isEqualTo("Hello, Bob!");
      assertThat(targetInvocations).containsExactly("Alice", "Bob");
    }
  }
}
