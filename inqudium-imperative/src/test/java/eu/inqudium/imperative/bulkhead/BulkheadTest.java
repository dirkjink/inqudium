package eu.inqudium.imperative.bulkhead;

import eu.inqudium.core.config.InqConfig;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.element.bulkhead.config.BulkheadEventConfig;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.event.InqEvent;
import eu.inqudium.core.pipeline.Wrapper;
import eu.inqudium.core.pipeline.proxy.InqProxyFactory;
import eu.inqudium.imperative.core.pipeline.InqAsyncProxyFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static eu.inqudium.imperative.bulkhead.config.InqImperativeBulkheadConfigBuilder.bulkhead;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Bulkhead")
class BulkheadTest {

  // =========================================================================
  // Service interface for proxy tests
  // =========================================================================

  private static Bulkhead<Void, Object> createBulkhead(String name, int maxConcurrent) {
    var config = InqConfig.configure()
        .general()
        .with(bulkhead(), c -> c
            .name(name)
            .maxConcurrentCalls(maxConcurrent)
        ).build();
    return Bulkhead.of(config);
  }

  public interface InventoryService {
    String checkStock(String sku);

    void reorder(String sku);

    CompletionStage<String> checkStockAsync(String sku);

    CompletionStage<Void> reorderAsync(String sku);
  }

  // =========================================================================
  // Helper: create a standard bulkhead for tests
  // =========================================================================

  static class RealInventoryService implements InventoryService {
    volatile CompletionStage<String> originalString;
    volatile CompletionStage<Void> originalVoid;

    @Override
    public String checkStock(String sku) {
      return "in-stock:" + sku;
    }

    @Override
    public void reorder(String sku) { /* no-op */ }

    @Override
    public CompletionStage<String> checkStockAsync(String sku) {
      return originalString = CompletableFuture.completedFuture("async-in-stock:" + sku);
    }

    @Override
    public CompletionStage<Void> reorderAsync(String sku) {
      return originalVoid = CompletableFuture.completedFuture(null);
    }
  }

  @Nested
  @DisplayName("Permit management")
  class PermitManagement {

    @Test
    void should_permit_calls_below_max_concurrent() {
      // Given
      var config = InqConfig.configure()
          .general()
          .with(bulkhead(), c -> c
              .name("bulkhead-1")
              .maxConcurrentCalls(5)
          ).build();
      var bh = Bulkhead.of(config);

      // When / Then
      var result = bh.executeSupplier(() -> "ok");
      assertThat(result).isEqualTo("ok");
    }

    @Test
    void should_release_permit_after_successful_call() {
      // Given
      var config = InqConfig.configure()
          .general()
          .with(bulkhead(), c -> c
              .name("bulkhead-1")
              .maxConcurrentCalls(1)
          ).build();
      var bh = Bulkhead.of(config);

      // When — call completes, permit should be released
      bh.executeSupplier(() -> "first");

      // Then — second call should succeed (permit was released)
      var result = bh.executeSupplier(() -> "second");
      assertThat(result).isEqualTo("second");
    }

    @Test
    void should_release_permit_after_failed_call() {
      // Given
      var config = InqConfig.configure()
          .general()
          .with(bulkhead(), c -> c
              .name("bulkhead-1")
              .maxConcurrentCalls(1)
          ).build();
      var bh = Bulkhead.of(config);

      // When — call fails, permit should still be released
      try {
        bh.executeSupplier(() -> {
          throw new RuntimeException("boom");
        });
      } catch (RuntimeException ignored) {
      }

      // Then — next call should succeed
      var result = bh.executeSupplier(() -> "recovered");
      assertThat(result).isEqualTo("recovered");
    }
  }

  @Nested
  @DisplayName("Rejection")
  class Rejection {

    @Test
    void should_reject_when_all_permits_are_held() throws Exception {
      // Given — 1 permit, held by a blocking call
      var config = InqConfig.configure()
          .general()
          .with(bulkhead(), c -> c
              .name("bulkhead-1")
              .maxConcurrentCalls(1)
              .eventConfig(BulkheadEventConfig.diagnostic())
          ).build();
      var bh = Bulkhead.of(config);

      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);

      // Hold the single permit
      var executor = Executors.newSingleThreadExecutor();
      executor.submit(() -> bh.executeSupplier(() -> {
        entered.countDown();
        try {
          release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return "blocking";
      }));

      entered.await(2, TimeUnit.SECONDS);

      // When / Then — second call should be rejected
      assertThatThrownBy(() -> bh.executeSupplier(() -> "rejected"))
          .isInstanceOf(InqBulkheadFullException.class)
          .satisfies(ex -> {
            var bfe = (InqBulkheadFullException) ex;
            assertThat(bfe.getCode()).isEqualTo("INQ-BH-001");
          });

      release.countDown();
      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Metrics")
  class Metrics {

    @Test
    void should_report_zero_concurrent_calls_when_idle() {
      // Given
      var config = InqConfig.configure()
          .general()
          .with(bulkhead(), c -> c
              .name("bulkhead-1")
              .maxConcurrentCalls(10)
          ).build();
      var bh = Bulkhead.of(config);

      // Then
      assertThat(bh.getConcurrentCalls()).isZero();
      assertThat(bh.getAvailablePermits()).isEqualTo(10);
    }

    @Test
    void should_report_correct_available_permits_after_call() {
      // Given
      var config = InqConfig.configure()
          .general()
          .with(bulkhead(), c -> c
              .name("bulkhead-1")
              .maxConcurrentCalls(5)
          ).build();
      var bh = Bulkhead.of(config);

      // When — call completes (permit acquired and released)
      bh.executeSupplier(() -> "ok");

      // Then — all permits restored
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }
  }

  @Nested
  @DisplayName("Event publishing")
  class EventPublishing {

    @Test
    void should_emit_acquire_and_release_events_for_successful_call() {
      // Given
      var config = InqConfig.configure()
          .general()
          .with(bulkhead(), c -> c
              .name("bulkhead-1")
              .maxConcurrentCalls(5)
              .eventConfig(BulkheadEventConfig.diagnostic())
          ).build();
      var bh = Bulkhead.of(config);
      var events = Collections.synchronizedList(new ArrayList<InqEvent>());
      bh.getEventPublisher().onEvent(InqEvent.class, events::add);

      // When
      bh.executeSupplier(() -> "ok");

      // Then
      assertThat(events).hasSize(2);
      assertThat(events.get(0)).isInstanceOf(BulkheadOnAcquireEvent.class);
      assertThat(events.get(1)).isInstanceOf(BulkheadOnReleaseEvent.class);
    }

    @Test
    void should_emit_reject_event_when_full() throws Exception {
      // Given
      var config = InqConfig.configure()
          .general()
          .with(bulkhead(), c -> c
              .name("bulkhead-1")
              .maxConcurrentCalls(1)
              .eventConfig(BulkheadEventConfig.diagnostic())
          ).build();
      var bh = Bulkhead.of(config);
      var events = Collections.synchronizedList(new ArrayList<InqEvent>());
      bh.getEventPublisher().onEvent(events::add);

      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var executor = Executors.newSingleThreadExecutor();

      executor.submit(() -> bh.executeSupplier(() -> {
        entered.countDown();
        try {
          release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return "blocking";
      }));

      entered.await(2, TimeUnit.SECONDS);

      // When
      try {
        bh.executeSupplier(() -> "rejected");
      } catch (InqBulkheadFullException ignored) {
      }

      // Then — acquire + reject (release comes later when blocking call finishes)
      assertThat(events.stream().filter(e -> e instanceof BulkheadOnRejectEvent).count()).isEqualTo(1);

      release.countDown();
      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Runnable decoration")
  class RunnableDecoration {

    @Test
    void should_decorate_runnable_with_acquire_and_release() {
      // Given
      var config = InqConfig.configure()
          .general()
          .with(bulkhead(), c -> c
              .name("bulkhead-1")
              .maxConcurrentCalls(5)
          ).build();
      var bh = Bulkhead.of(config);
      var executed = new AtomicBoolean(false);

      // When
      bh.executeRunnable(() -> executed.set(true));

      // Then
      assertThat(executed.get()).isTrue();
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }
  }

  // ════════════════════════════════════════════════════════════════════
  // Sync decorate-then-invoke
  // ════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Sync decorate-then-invoke")
  class SyncDecorateThenInvoke {

    @Test
    @DisplayName("should decorate a Supplier and return the correct result on get()")
    void should_decorate_a_Supplier_and_return_the_correct_result_on_get() {
      // Given
      var bh = createBulkhead("sync-supplier", 5);
      Supplier<String> decorated = bh.decorateSupplier(() -> "hello");

      // When
      String result = decorated.get();

      // Then
      assertThat(result).isEqualTo("hello");
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }

    @Test
    @DisplayName("should decorate a Runnable and release permits after run()")
    void should_decorate_a_Runnable_and_release_permits_after_run() {
      // Given
      var bh = createBulkhead("sync-runnable", 5);
      var executed = new AtomicBoolean(false);
      Runnable decorated = bh.decorateRunnable(() -> executed.set(true));

      // When
      decorated.run();

      // Then
      assertThat(executed.get()).isTrue();
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }

    @Test
    @DisplayName("should decorate a Callable and return the correct result on call()")
    void should_decorate_a_Callable_and_return_the_correct_result_on_call() throws Exception {
      // Given
      var bh = createBulkhead("sync-callable", 5);
      Callable<Integer> decorated = bh.decorateCallable(() -> 42);

      // When
      int result = decorated.call();

      // Then
      assertThat(result).isEqualTo(42);
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }

    @Test
    @DisplayName("should release permit after decorated Supplier throws an exception")
    void should_release_permit_after_decorated_Supplier_throws_an_exception() {
      // Given
      var bh = createBulkhead("sync-supplier-fail", 1);
      Supplier<String> decorated = bh.decorateSupplier(() -> {
        throw new RuntimeException("fail");
      });

      // When
      try {
        decorated.get();
      } catch (RuntimeException ignored) {
      }

      // Then — permit released, next call succeeds
      Supplier<String> second = bh.decorateSupplier(() -> "recovered");
      assertThat(second.get()).isEqualTo("recovered");
    }

    @Test
    @DisplayName("should reject decorated Supplier when bulkhead is full")
    void should_reject_decorated_Supplier_when_bulkhead_is_full() throws Exception {
      // Given
      var bh = createBulkhead("sync-supplier-reject", 1);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);

      Supplier<String> blocking = bh.decorateSupplier(() -> {
        entered.countDown();
        try {
          release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return "blocking";
      });

      var executor = Executors.newSingleThreadExecutor();
      executor.submit(blocking::get);
      entered.await(2, TimeUnit.SECONDS);

      // When / Then
      Supplier<String> rejected = bh.decorateSupplier(() -> "should-not-run");
      assertThatThrownBy(rejected::get).isInstanceOf(InqBulkheadFullException.class);

      release.countDown();
      executor.shutdown();
    }

    @Test
    @DisplayName("should allow reuse of a single decorated Supplier across multiple invocations")
    void should_allow_reuse_of_a_single_decorated_Supplier_across_multiple_invocations() {
      // Given
      var bh = createBulkhead("sync-reuse", 5);
      var counter = new java.util.concurrent.atomic.AtomicInteger();
      Supplier<Integer> decorated = bh.decorateSupplier(counter::incrementAndGet);

      // When
      int first = decorated.get();
      int second = decorated.get();
      int third = decorated.get();

      // Then
      assertThat(decorated)
          .isInstanceOfSatisfying(Wrapper.class, wrapper -> {
            long chainId = wrapper.chainId();
            String layerDescription = wrapper.layerDescription();
            long currentCallId = wrapper.currentCallId();
            assertThat(layerDescription).isEqualTo("BULKHEAD(sync-reuse)");
            assertThat(wrapper.toStringHierarchy()).isEqualToIgnoringNewLines(
                "Chain-ID: " + chainId + " (current call-ID: " + currentCallId + ")" + layerDescription);
          });
      assertThat(first).isEqualTo(1);
      assertThat(second).isEqualTo(2);
      assertThat(third).isEqualTo(3);
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }
  }

  // ════════════════════════════════════════════════════════════════════
  // Async decorate-then-invoke
  // ════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Async decorate-then-invoke")
  class AsyncDecorateThenInvoke {

    @Test
    @DisplayName("should decorate an async Supplier and return the correct result on get()")
    void should_decorate_an_async_Supplier_and_return_the_correct_result_on_get() {
      // Given
      CompletableFuture<String> originalFuture = CompletableFuture.completedFuture("async-hello");

      var bh = createBulkhead("async-supplier", 5);
      Supplier<CompletionStage<String>> decorated = bh.decorateAsyncSupplier(() -> originalFuture);

      // When
      CompletionStage<String> stringCompletionStage = decorated.get();
      String result = ((CompletableFuture<String>) stringCompletionStage).join();

      // Then
      assertThat(stringCompletionStage).isSameAs(originalFuture);
      assertThat(result).isEqualTo("async-hello");
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }

    @Test
    @DisplayName("should decorate an async Runnable and release permits on completion")
    void should_decorate_an_async_Runnable_and_release_permits_on_completion() {
      // Given
      var bh = createBulkhead("async-runnable", 5);
      var executed = new AtomicBoolean(false);
      Supplier<CompletionStage<Void>> decorated =
          bh.decorateAsyncRunnable(() -> executed.set(true));

      // When
      ((CompletableFuture<Void>) decorated.get()).join();

      // Then
      assertThat(executed.get()).isTrue();
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }

    @Test
    @DisplayName("should release permit when the async stage completes exceptionally")
    void should_release_permit_when_the_async_stage_completes_exceptionally() {
      // Given
      var bh = createBulkhead("async-fail", 1);
      Supplier<CompletionStage<String>> decorated = bh.decorateAsyncSupplier(
          () -> CompletableFuture.failedFuture(new RuntimeException("async-boom")));

      // When
      try {
        ((CompletableFuture<String>) decorated.get()).join();
      } catch (Exception ignored) {
      }

      // Then — permit released, next call succeeds
      Supplier<CompletionStage<String>> second = bh.decorateAsyncSupplier(
          () -> CompletableFuture.completedFuture("recovered"));
      assertThat(((CompletableFuture<String>) second.get()).join()).isEqualTo("recovered");
    }

    @Test
    @DisplayName("should reject async Supplier when bulkhead is full")
    void should_reject_async_Supplier_when_bulkhead_is_full() throws Exception {
      // Given
      var bh = createBulkhead("async-reject", 1);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);

      Supplier<CompletionStage<String>> blocking = bh.decorateAsyncSupplier(() -> {
        entered.countDown();
        return CompletableFuture.supplyAsync(() -> {
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
          }
          return "blocking";
        });
      });

      // Hold the permit — the stage is not yet completed, but the permit is held
      var executor = Executors.newSingleThreadExecutor();
      executor.submit(blocking::get);
      entered.await(2, TimeUnit.SECONDS);

      // When / Then — second decorated supplier should be rejected at acquire
      Supplier<CompletionStage<String>> rejected = bh.decorateAsyncSupplier(
          () -> CompletableFuture.completedFuture("should-not-run"));
      assertThatThrownBy(rejected::get).isInstanceOf(InqBulkheadFullException.class);

      release.countDown();
      executor.shutdown();
    }

    @Test
    @DisplayName("should allow reuse of a single async decorated Supplier across multiple invocations")
    void should_allow_reuse_of_a_single_async_decorated_Supplier_across_multiple_invocations() {
      // Given
      var bh = createBulkhead("async-reuse", 5);
      var counter = new java.util.concurrent.atomic.AtomicInteger();
      Supplier<CompletionStage<Integer>> decorated = bh.decorateAsyncSupplier(
          () -> CompletableFuture.completedFuture(counter.incrementAndGet()));

      // When
      int first = ((CompletableFuture<Integer>) decorated.get()).join();
      int second = ((CompletableFuture<Integer>) decorated.get()).join();

      // Then
      assertThat(decorated)
          .isInstanceOfSatisfying(Wrapper.class, wrapper -> {
            long chainId = wrapper.chainId();
            String layerDescription = wrapper.layerDescription();
            long currentCallId = wrapper.currentCallId();
            assertThat(layerDescription).isEqualTo("BULKHEAD(async-reuse)");
            assertThat(wrapper.toStringHierarchy()).isEqualToIgnoringNewLines(
                "Chain-ID: " + chainId + " (current call-ID: " + currentCallId + ")" + layerDescription);
          });
      assertThat(first).isEqualTo(1);
      assertThat(second).isEqualTo(2);
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }
  }

  // ════════════════════════════════════════════════════════════════════
  // Sync dynamic proxy
  // ════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Sync dynamic proxy")
  class SyncDynamicProxy {

    @Test
    @DisplayName("should protect a service interface and return correct results")
    void should_protect_a_service_interface_and_return_correct_results() {
      // Given
      var bh = createBulkhead("proxy-sync", 5);
      var factory = new BulkheadProxyFactory(bh);
      InventoryService proxy = factory.protect(InventoryService.class, new RealInventoryService());

      // When
      String result = proxy.checkStock("SKU-001");

      // Then
      assertThat(proxy)
          .isInstanceOfSatisfying(Wrapper.class, wrapper -> {
            long chainId = wrapper.chainId();
            String layerDescription = wrapper.layerDescription();
            long currentCallId = wrapper.currentCallId();
            assertThat(layerDescription).isEqualTo("proxy-sync");
            assertThat(wrapper.toStringHierarchy()).isEqualToIgnoringNewLines(
                "Chain-ID: " + chainId + " (current call-ID: " + currentCallId + ")" + layerDescription);
          });
      assertThat(result).isEqualTo("in-stock:SKU-001");
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }

    @Test
    @DisplayName("should protect void methods on the proxied service")
    void should_protect_void_methods_on_the_proxied_service() {
      // Given
      var bh = createBulkhead("proxy-void", 5);
      var factory = new BulkheadProxyFactory(bh);
      InventoryService proxy = factory.protect(InventoryService.class, new RealInventoryService());

      // When / Then — no exception, permits restored
      proxy.reorder("SKU-001");
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }

    @Test
    @DisplayName("should release permit after proxied method throws an exception")
    void should_release_permit_after_proxied_method_throws_an_exception() {
      // Given
      var bh = createBulkhead("proxy-fail", 1);
      var factory = new BulkheadProxyFactory(bh);
      InventoryService failing = new InventoryService() {
        @Override
        public String checkStock(String sku) {
          throw new RuntimeException("out-of-stock");
        }

        @Override
        public void reorder(String sku) {
        }

        @Override
        public CompletionStage<String> checkStockAsync(String sku) {
          return null;
        }

        @Override
        public CompletionStage<Void> reorderAsync(String sku) {
          return null;
        }
      };
      InventoryService proxy = factory.protect(InventoryService.class, failing);

      // When
      try {
        proxy.checkStock("SKU-001");
      } catch (RuntimeException ignored) {
      }

      // Then — permit released
      InventoryService goodProxy = factory.protect(InventoryService.class, new RealInventoryService());
      assertThat(goodProxy.checkStock("SKU-002")).isEqualTo("in-stock:SKU-002");
    }

    @Test
    @DisplayName("should reject proxied call when bulkhead is full")
    void should_reject_proxied_call_when_bulkhead_is_full() throws Exception {
      // Given
      var bh = createBulkhead("proxy-reject", 1);
      var factory = new BulkheadProxyFactory(bh);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);

      InventoryService blocking = new InventoryService() {
        @Override
        public String checkStock(String sku) {
          entered.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
          }
          return "blocking";
        }

        @Override
        public void reorder(String sku) {
        }

        @Override
        public CompletionStage<String> checkStockAsync(String sku) {
          return null;
        }

        @Override
        public CompletionStage<Void> reorderAsync(String sku) {
          return null;
        }
      };
      InventoryService blockingProxy = factory.protect(InventoryService.class, blocking);

      var executor = Executors.newSingleThreadExecutor();
      executor.submit(() -> blockingProxy.checkStock("SKU-001"));
      entered.await(2, TimeUnit.SECONDS);

      // When / Then
      InventoryService rejectedProxy = factory.protect(InventoryService.class, new RealInventoryService());
      assertThatThrownBy(() -> rejectedProxy.checkStock("SKU-002"))
          .isInstanceOf(InqBulkheadFullException.class);

      release.countDown();
      executor.shutdown();
    }

    @Test
    @DisplayName("should expose Wrapper interface on the proxy for chain visualization")
    void should_expose_Wrapper_interface_on_the_proxy_for_chain_visualization() {
      // Given
      var bh = createBulkhead("proxy-wrapper", 5);
      var factory = new BulkheadProxyFactory(bh);
      InventoryService proxy = factory.protect(InventoryService.class, new RealInventoryService());

      // When
      Wrapper<?> wrapper = (Wrapper<?>) proxy;

      // Then
      assertThat(wrapper.chainId()).isGreaterThan(0L);
      assertThat(wrapper.layerDescription()).isEqualTo("proxy-wrapper");
      assertThat(wrapper.toStringHierarchy()).contains("proxy-wrapper");
    }

    @Test
    @DisplayName("should support stacking multiple proxy layers with shared ids")
    void should_support_stacking_multiple_proxy_layers_with_shared_ids() {
      // Given
      var bh = createBulkhead("proxy-stack", 5);
      var bhFactory = new BulkheadProxyFactory(bh);

      var events = Collections.synchronizedList(new ArrayList<String>());
      InqProxyFactory loggingFactory = InqProxyFactory.of("logging",
          (chainId, callId, arg, next) -> {
            events.add("logging-before");
            Object result = next.execute(chainId, callId, arg);
            events.add("logging-after");
            return result;
          });

      // When — logging wraps bulkhead wraps real
      InventoryService inner = bhFactory.protect(InventoryService.class, new RealInventoryService());
      InventoryService outer = loggingFactory.protect(InventoryService.class, inner);

      String result = outer.checkStock("SKU-001");

      // Then
      assertThat(result).isEqualTo("in-stock:SKU-001");
      assertThat(events).containsExactly("logging-before", "logging-after");

      // Chain shares the same chain ID
      Wrapper<?> outerWrapper = (Wrapper<?>) outer;
      Wrapper<?> innerWrapper = outerWrapper.inner();
      assertThat(outerWrapper.chainId()).isEqualTo(innerWrapper.chainId());
      assertThat(outerWrapper.layerDescription()).isEqualTo("logging");
      assertThat(innerWrapper.layerDescription()).isEqualTo("proxy-stack");
    }
  }

  // ════════════════════════════════════════════════════════════════════
  // Async dynamic proxy
  // ════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Async dynamic proxy")
  class AsyncDynamicProxy {

    @Test
    @DisplayName("should route async methods through the async chain and return correct results")
    void should_route_async_methods_through_the_async_chain_and_return_correct_results() {
      // Given
      var bh = createBulkhead("proxy-async", 5);
      var factory = new BulkheadProxyFactory(bh);
      InventoryService proxy = factory.protect(InventoryService.class, new RealInventoryService());

      // When — async method returns CompletionStage
      CompletionStage<String> stage = proxy.checkStockAsync("SKU-001");
      String result = ((CompletableFuture<String>) stage).join();

      // Then
      assertThat(proxy)
          .isInstanceOfSatisfying(Wrapper.class, wrapper -> {
            long chainId = wrapper.chainId();
            String layerDescription = wrapper.layerDescription();
            long currentCallId = wrapper.currentCallId();
            assertThat(layerDescription).isEqualTo("proxy-async");
            assertThat(wrapper.toStringHierarchy()).isEqualToIgnoringNewLines(
                "Chain-ID: " + chainId + " (current call-ID: " + currentCallId + ")" + layerDescription);
          });
      assertThat(result).isEqualTo("async-in-stock:SKU-001");
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }

    @Test
    @DisplayName("should route sync methods through the sync chain on the same proxy")
    void should_route_sync_methods_through_the_sync_chain_on_the_same_proxy() {
      // Given — same proxy handles both sync and async methods
      var bh = createBulkhead("proxy-mixed", 5);
      var factory = new BulkheadProxyFactory(bh);
      InventoryService proxy = factory.protect(InventoryService.class, new RealInventoryService());

      // When
      String syncResult = proxy.checkStock("SKU-001");
      String asyncResult = ((CompletableFuture<String>) proxy.checkStockAsync("SKU-002")).join();

      // Then — both paths work on the same proxy
      assertThat(syncResult).isEqualTo("in-stock:SKU-001");
      assertThat(asyncResult).isEqualTo("async-in-stock:SKU-002");
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }

    @Test
    @DisplayName("should release permit when async stage completes exceptionally through proxy")
    void should_release_permit_when_async_stage_completes_exceptionally_through_proxy() {
      // Given
      var bh = createBulkhead("proxy-async-fail", 1);
      var factory = new BulkheadProxyFactory(bh);
      InventoryService failing = new InventoryService() {
        @Override
        public String checkStock(String sku) {
          return "";
        }

        @Override
        public void reorder(String sku) {
        }

        @Override
        public CompletionStage<String> checkStockAsync(String sku) {
          return CompletableFuture.failedFuture(new RuntimeException("async-fail"));
        }

        @Override
        public CompletionStage<Void> reorderAsync(String sku) {
          return null;
        }
      };
      InventoryService proxy = factory.protect(InventoryService.class, failing);

      // When
      try {
        ((CompletableFuture<String>) proxy.checkStockAsync("SKU-001")).join();
      } catch (Exception ignored) {
      }

      // Then — permit released, next call succeeds
      InventoryService goodProxy = factory.protect(InventoryService.class, new RealInventoryService());
      String result = ((CompletableFuture<String>) goodProxy.checkStockAsync("SKU-002")).join();
      assertThat(result).isEqualTo("async-in-stock:SKU-002");
    }

    @Test
    @DisplayName("should handle void async methods through proxy")
    void should_handle_void_async_methods_through_proxy() {
      // Given
      var bh = createBulkhead("proxy-async-void", 5);
      var factory = new BulkheadProxyFactory(bh);
      RealInventoryService target = new RealInventoryService();
      InventoryService proxy = factory.protect(InventoryService.class, target);

      // When
      CompletionStage<Void> stage = proxy.reorderAsync("SKU-001");
      ((CompletableFuture<Void>) stage).join();

      // Then
      assertThat(stage).isSameAs(target.originalVoid);
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }

    @Test
    @DisplayName("should support stacking async proxy layers with shared ids")
    void should_support_stacking_async_proxy_layers_with_shared_ids() {
      // Given
      var bh = createBulkhead("proxy-async-stack", 5);
      var bhFactory = new BulkheadProxyFactory(bh);

      var events = Collections.synchronizedList(new ArrayList<String>());
      InqAsyncProxyFactory loggingFactory = InqAsyncProxyFactory.of("async-logging",
          // Sync path
          (chainId, callId, arg, next) -> {
            events.add("sync-logging");
            return next.execute(chainId, callId, arg);
          },
          // Async path
          (chainId, callId, arg, next) -> {
            events.add("async-logging-start");
            return next.executeAsync(chainId, callId, arg)
                .whenComplete((r, e) -> events.add("async-logging-end"));
          });

      // When — logging wraps bulkhead wraps real
      InventoryService inner = bhFactory.protect(InventoryService.class, new RealInventoryService());
      InventoryService outer = loggingFactory.protect(InventoryService.class, inner);

      String result = ((CompletableFuture<String>) outer.checkStockAsync("SKU-001")).join();

      // Then
      assertThat(result).isEqualTo("async-in-stock:SKU-001");
      assertThat(events).containsExactly("async-logging-start", "async-logging-end");

      // Chain shares the same chain ID
      Wrapper<?> outerWrapper = (Wrapper<?>) outer;
      Wrapper<?> innerWrapper = outerWrapper.inner();
      assertThat(outerWrapper.chainId()).isEqualTo(innerWrapper.chainId());
    }
  }
}
