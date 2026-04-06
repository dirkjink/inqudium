package eu.inqudium.core.pipeline.proxy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MethodHandleCacheTest {

  private MethodHandleCache cache;

  @BeforeEach
  void setUp() {
    cache = new MethodHandleCache();
  }

  // ======================== Test target class ========================

  @SuppressWarnings("unused")
  public static class Target {
    public String zeroArgs() {
      return "zero";
    }

    public String oneArg(String a) {
      return "one:" + a;
    }

    public String twoArgs(String a, String b) {
      return "two:" + a + "," + b;
    }

    public String threeArgs(String a, String b, String c) {
      return "three:" + a + "," + b + "," + c;
    }

    public String fourArgs(String a, String b, String c, String d) {
      return "four:" + a + "," + b + "," + c + "," + d;
    }

    public String fiveArgs(String a, String b, String c, String d, String e) {
      return "five:" + a + "," + b + "," + c + "," + d + "," + e;
    }

    public int add(int a, int b) {
      return a + b;
    }

    public void voidMethod() {
      // intentionally empty
    }

    public Object returnsNull() {
      return null;
    }

    public String throwsException() {
      throw new IllegalStateException("boom");
    }

    public String throwsChecked() throws Exception {
      throw new Exception("checked-boom");
    }
  }

  // ======================== Helper ========================

  private static Method method(String name, Class<?>... paramTypes) {
    try {
      return Target.class.getMethod(name, paramTypes);
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  // ======================== Tests ========================

  @Nested
  class Resolve {

    @Test
    void returns_a_non_null_method_handle_for_a_valid_method() {
      // Given
      Method m = method("zeroArgs");

      // When
      MethodHandle handle = cache.resolve(m);

      // Then
      assertThat(handle).isNotNull();
    }

    @Test
    void returns_the_same_handle_instance_on_repeated_resolve_calls() {
      // Given
      Method m = method("zeroArgs");

      // When
      MethodHandle first = cache.resolve(m);
      MethodHandle second = cache.resolve(m);

      // Then
      assertThat(first).isSameAs(second);
    }

    @Test
    void returns_different_handles_for_different_methods() {
      // Given
      Method m1 = method("zeroArgs");
      Method m2 = method("oneArg", String.class);

      // When
      MethodHandle h1 = cache.resolve(m1);
      MethodHandle h2 = cache.resolve(m2);

      // Then
      assertThat(h1).isNotSameAs(h2);
    }
  }

  @Nested
  class Invoke_with_zero_arguments {

    @Test
    void dispatches_a_zero_arg_method_correctly() throws Throwable {
      // Given
      Method m = method("zeroArgs");
      Target target = new Target();

      // When
      Object result = cache.invoke(m, target, null);

      // Then
      assertThat(result).isEqualTo("zero");
    }

    @Test
    void dispatches_a_zero_arg_method_when_args_is_empty_array() throws Throwable {
      // Given
      Method m = method("zeroArgs");
      Target target = new Target();

      // When
      Object result = cache.invoke(m, target, new Object[0]);

      // Then
      assertThat(result).isEqualTo("zero");
    }

    @Test
    void handles_void_return_type() throws Throwable {
      // Given
      Method m = method("voidMethod");
      Target target = new Target();

      // When
      Object result = cache.invoke(m, target, null);

      // Then
      assertThat(result).isNull();
    }

    @Test
    void handles_null_return_value() throws Throwable {
      // Given
      Method m = method("returnsNull");
      Target target = new Target();

      // When
      Object result = cache.invoke(m, target, null);

      // Then
      assertThat(result).isNull();
    }
  }

  @Nested
  class Invoke_with_one_argument {

    @Test
    void dispatches_a_single_arg_method_correctly() throws Throwable {
      // Given
      Method m = method("oneArg", String.class);
      Target target = new Target();

      // When
      Object result = cache.invoke(m, target, new Object[]{"hello"});

      // Then
      assertThat(result).isEqualTo("one:hello");
    }
  }

  @Nested
  class Invoke_with_two_arguments {

    @Test
    void dispatches_a_two_arg_method_correctly() throws Throwable {
      // Given
      Method m = method("twoArgs", String.class, String.class);
      Target target = new Target();

      // When
      Object result = cache.invoke(m, target, new Object[]{"a", "b"});

      // Then
      assertThat(result).isEqualTo("two:a,b");
    }

    @Test
    void dispatches_primitive_args_with_correct_boxing() throws Throwable {
      // Given
      Method m = method("add", int.class, int.class);
      Target target = new Target();

      // When
      Object result = cache.invoke(m, target, new Object[]{3, 7});

      // Then
      assertThat(result).isEqualTo(10);
    }
  }

  @Nested
  class Invoke_with_three_arguments {

    @Test
    void dispatches_a_three_arg_method_correctly() throws Throwable {
      // Given
      Method m = method("threeArgs", String.class, String.class, String.class);
      Target target = new Target();

      // When
      Object result = cache.invoke(m, target, new Object[]{"a", "b", "c"});

      // Then
      assertThat(result).isEqualTo("three:a,b,c");
    }
  }

  @Nested
  class Invoke_with_high_arity_via_spreader {

    @Test
    void dispatches_a_four_arg_method_through_the_spreader_path() throws Throwable {
      // Given
      Method m = method("fourArgs", String.class, String.class, String.class, String.class);
      Target target = new Target();

      // When
      Object result = cache.invoke(m, target, new Object[]{"a", "b", "c", "d"});

      // Then
      assertThat(result).isEqualTo("four:a,b,c,d");
    }

    @Test
    void dispatches_a_five_arg_method_through_the_spreader_path() throws Throwable {
      // Given
      Method m = method("fiveArgs", String.class, String.class, String.class, String.class, String.class);
      Target target = new Target();

      // When
      Object result = cache.invoke(m, target, new Object[]{"a", "b", "c", "d", "e"});

      // Then
      assertThat(result).isEqualTo("five:a,b,c,d,e");
    }

    @Test
    void caches_the_spreader_handle_across_invocations() throws Throwable {
      // Given
      Method m = method("fourArgs", String.class, String.class, String.class, String.class);
      Target target = new Target();

      // When — invoke twice to ensure spreader is reused
      Object first = cache.invoke(m, target, new Object[]{"a", "b", "c", "d"});
      Object second = cache.invoke(m, target, new Object[]{"w", "x", "y", "z"});

      // Then
      assertThat(first).isEqualTo("four:a,b,c,d");
      assertThat(second).isEqualTo("four:w,x,y,z");
    }
  }

  @Nested
  class Exception_propagation {

    @Test
    void propagates_runtime_exceptions_from_the_target_method() {
      // Given
      Method m = method("throwsException");
      Target target = new Target();

      // When / Then
      assertThatThrownBy(() -> cache.invoke(m, target, null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("boom");
    }

    @Test
    void propagates_checked_exceptions_from_the_target_method() {
      // Given
      Method m = method("throwsChecked");
      Target target = new Target();

      // When / Then
      assertThatThrownBy(() -> cache.invoke(m, target, null))
          .isInstanceOf(Exception.class)
          .hasMessage("checked-boom");
    }
  }

  @Nested
  class Thread_safety {

    @Test
    void concurrent_resolve_calls_return_the_same_handle() throws InterruptedException {
      // Given
      Method m = method("zeroArgs");
      int threadCount = 16;
      ExecutorService pool = Executors.newFixedThreadPool(threadCount);
      CountDownLatch latch = new CountDownLatch(1);
      List<MethodHandle> handles = new CopyOnWriteArrayList<>();

      // When
      for (int i = 0; i < threadCount; i++) {
        pool.submit(() -> {
          try {
            latch.await();
            handles.add(cache.resolve(m));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
      }
      latch.countDown();
      pool.shutdown();
      pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

      // Then — all threads must have gotten the same MethodHandle instance
      assertThat(handles).hasSize(threadCount);
      assertThat(handles).allSatisfy(h -> assertThat(h).isSameAs(handles.get(0)));
    }

    @Test
    void concurrent_invoke_calls_produce_correct_results() throws InterruptedException {
      // Given
      Method m = method("oneArg", String.class);
      Target target = new Target();
      int threadCount = 16;
      ExecutorService pool = Executors.newFixedThreadPool(threadCount);
      CountDownLatch latch = new CountDownLatch(1);
      List<Object> results = new CopyOnWriteArrayList<>();

      // When
      for (int i = 0; i < threadCount; i++) {
        int idx = i;
        pool.submit(() -> {
          try {
            latch.await();
            results.add(cache.invoke(m, target, new Object[]{"t" + idx}));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Throwable e) {
            results.add("ERROR:" + e.getMessage());
          }
        });
      }
      latch.countDown();
      pool.shutdown();
      pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

      // Then
      assertThat(results).hasSize(threadCount);
      for (int i = 0; i < threadCount; i++) {
        assertThat(results).contains("one:t" + i);
      }
    }
  }

  @Nested
  class Separate_cache_instances_are_independent {

    @Test
    void two_cache_instances_resolve_independently() {
      // Given
      MethodHandleCache cache1 = new MethodHandleCache();
      MethodHandleCache cache2 = new MethodHandleCache();
      Method m = method("zeroArgs");

      // When
      MethodHandle h1 = cache1.resolve(m);
      MethodHandle h2 = cache2.resolve(m);

      // Then — different cache instances produce independent handle objects
      assertThat(h1).isNotSameAs(h2);
    }
  }
}
