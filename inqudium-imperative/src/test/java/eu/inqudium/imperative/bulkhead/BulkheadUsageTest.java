package eu.inqudium.imperative.bulkhead;

import eu.inqudium.core.Invocation;
import eu.inqudium.core.InvocationVarargs;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.exception.InqFailure;
import eu.inqudium.core.pipeline.InqPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("Bulkhead — User Perspective")
class BulkheadUsageTest {

  interface OrderApi {
    String processOrder(String orderId);

    String processOrderDetailed(String orderId, String region, int priority, boolean expedited);
  }

  static class OrderService implements OrderApi {
    private final AtomicInteger callCount = new AtomicInteger(0);

    @Override
    public String processOrder(String orderId) {
      callCount.incrementAndGet();
      return "processed-" + orderId;
    }

    @Override
    public String processOrderDetailed(String orderId, String region, int priority, boolean expedited) {
      callCount.incrementAndGet();
      return String.format("%s@%s-p%d-%s", orderId, region, priority,
          expedited ? "expedited" : "normal");
    }

    String processOrderSlowly(String orderId, CountDownLatch holdLatch) {
      callCount.incrementAndGet();
      try {
        holdLatch.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return "processed-" + orderId;
    }

    int getCallCount() {
      return callCount.get();
    }
  }

  @Nested
  @DisplayName("Standalone usage")
  class Standalone {

    @Test
    void should_let_calls_through_when_bulkhead_has_capacity() {
      // Given
      var service = new OrderService();
      var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
          .maxConcurrentCalls(5)
          .build());
      Supplier<String> resilientProcess = bh.decorateSupplier(() -> service.processOrder("order-1"));

      // When
      var result = resilientProcess.get();

      // Then
      assertThat(result).isEqualTo("processed-order-1");
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }

    @Test
    void should_release_permits_after_failed_calls() {
      // Given
      var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
          .maxConcurrentCalls(2)
          .build());
      Supplier<String> resilient = bh.decorateSupplier(() -> {
        throw new RuntimeException("boom");
      });

      // When
      catchThrowable(resilient::get);

      // Then
      assertThat(bh.getAvailablePermits()).isEqualTo(2);
    }

    @Test
    void should_reject_calls_when_bulkhead_is_full() throws Exception {
      // Given
      var service = new OrderService();
      var holdLatch = new CountDownLatch(1);
      var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
          .maxConcurrentCalls(1)
          .build());
      Supplier<String> slowCall = bh.decorateSupplier(
          () -> service.processOrderSlowly("blocking", holdLatch));
      Supplier<String> fastCall = bh.decorateSupplier(() -> service.processOrder("rejected"));

      ExecutorService executor = Executors.newSingleThreadExecutor();
      executor.submit(slowCall::get);
      Thread.sleep(50);

      // When / Then
      assertThatThrownBy(fastCall::get)
          .isInstanceOf(InqBulkheadFullException.class)
          .satisfies(ex -> {
            var bhEx = (InqBulkheadFullException) ex;
            assertThat(bhEx.getCode()).isEqualTo("INQ-BH-001");
            assertThat(bhEx.getMaxConcurrentCalls()).isEqualTo(1);
          });

      holdLatch.countDown();
      executor.shutdown();
    }

    @Test
    void should_allow_catching_bulkhead_full_via_inq_failure() throws Exception {
      // Given
      var holdLatch = new CountDownLatch(1);
      var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
          .maxConcurrentCalls(1)
          .build());
      Supplier<String> slowCall = bh.decorateSupplier(() -> {
        try {
          holdLatch.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return "done";
      });
      Supplier<String> nextCall = bh.decorateSupplier(() -> "rejected");

      ExecutorService executor = Executors.newSingleThreadExecutor();
      executor.submit(slowCall::get);
      Thread.sleep(50);

      // When
      var handled = new AtomicInteger(0);
      try {
        nextCall.get();
      } catch (RuntimeException e) {
        InqFailure.find(e)
            .ifBulkheadFull(info -> handled.incrementAndGet())
            .orElseThrow();
      }

      // Then
      assertThat(handled).hasValue(1);
      holdLatch.countDown();
      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Standalone invocation usage")
  class StandaloneInvocation {

    @Test
    void should_isolate_a_single_argument_invocation_with_different_orders() throws Exception {
      // Given
      var service = new OrderService();
      var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
          .maxConcurrentCalls(5)
          .build());
      Invocation<String, String> resilientProcess =
          bh.decorateInvocation(service::processOrder);

      // When
      var r1 = resilientProcess.invoke("order-1");
      var r2 = resilientProcess.invoke("order-2");
      var r3 = resilientProcess.invoke("order-3");

      // Then
      assertThat(r1).isEqualTo("processed-order-1");
      assertThat(r2).isEqualTo("processed-order-2");
      assertThat(r3).isEqualTo("processed-order-3");
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
      assertThat(service.getCallCount()).isEqualTo(3);
    }

    @Test
    void should_isolate_a_four_argument_invocation_via_varargs() throws Exception {
      // Given
      var service = new OrderService();
      var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
          .maxConcurrentCalls(5)
          .build());
      InvocationVarargs<String> resilientProcess = bh.decorateInvocation(
          (InvocationVarargs<String>) args -> service.processOrderDetailed(
              (String) args[0], (String) args[1],
              (Integer) args[2], (Boolean) args[3]));

      // When
      var r1 = resilientProcess.invoke("order-1", "EU", 1, true);
      var r2 = resilientProcess.invoke("order-2", "US", 3, false);

      // Then
      assertThat(r1).isEqualTo("order-1@EU-p1-expedited");
      assertThat(r2).isEqualTo("order-2@US-p3-normal");
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }
  }

  @Nested
  @DisplayName("Pipeline usage")
  class Pipeline {

    @Test
    void should_isolate_a_call_through_the_pipeline() {
      // Given
      var service = new OrderService();
      var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
          .maxConcurrentCalls(5)
          .build());
      Supplier<String> resilient = InqPipeline.of(() -> service.processOrder("pipeline-1"))
          .shield(bh)
          .decorate();

      // When / Then
      assertThat(resilient.get()).isEqualTo("processed-pipeline-1");
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }

    @Test
    void should_carry_a_pipeline_call_id_on_bulkhead_full() throws Exception {
      // Given
      var holdLatch = new CountDownLatch(1);
      var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
          .maxConcurrentCalls(1)
          .build());
      Supplier<String> blocking = InqPipeline.of(() -> {
        try {
          holdLatch.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return "blocking";
      }).shield(bh).decorate();

      ExecutorService executor = Executors.newSingleThreadExecutor();
      executor.submit(blocking::get);
      Thread.sleep(50);

      Supplier<String> resilient = InqPipeline.of(() -> "rejected")
          .shield(bh).decorate();

      // When / Then
      assertThatThrownBy(resilient::get)
          .isInstanceOf(InqBulkheadFullException.class)
          .satisfies(ex -> assertThat(((InqException) ex).getCallId()).isNotEqualTo("None"));

      holdLatch.countDown();
      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Pipeline invocation usage")
  class PipelineInvocation {

    @Test
    void should_compose_pipeline_with_single_argument_invocation() throws Exception {
      // Given
      var service = new OrderService();
      var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
          .maxConcurrentCalls(5)
          .build());

      Invocation<String, String> resilientProcess = orderId ->
          InqPipeline.of(() -> service.processOrder(orderId))
              .shield(bh)
              .decorate()
              .get();

      // When
      var r1 = resilientProcess.invoke("order-1");
      var r2 = resilientProcess.invoke("order-2");

      // Then
      assertThat(r1).isEqualTo("processed-order-1");
      assertThat(r2).isEqualTo("processed-order-2");
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }

    @Test
    void should_compose_pipeline_with_four_argument_invocation() throws Exception {
      // Given
      var service = new OrderService();
      var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
          .maxConcurrentCalls(5)
          .build());

      InvocationVarargs<String> resilientProcess = args ->
          InqPipeline.of(() -> service.processOrderDetailed(
                  (String) args[0], (String) args[1],
                  (Integer) args[2], (Boolean) args[3]))
              .shield(bh)
              .decorate()
              .get();

      // When
      var r1 = resilientProcess.invoke("order-1", "EU", 1, true);
      var r2 = resilientProcess.invoke("order-2", "US", 3, false);

      // Then
      assertThat(r1).isEqualTo("order-1@EU-p1-expedited");
      assertThat(r2).isEqualTo("order-2@US-p3-normal");
    }
  }

  // ── Pipeline — Proxy pattern ──

  @Nested
  @DisplayName("Pipeline proxy usage")
  class PipelineProxy {

    @Test
    void should_create_a_typed_proxy_that_isolates_single_argument_calls() {
      // Given
      var service = new OrderService();
      var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
          .maxConcurrentCalls(5)
          .build());

      OrderApi resilient = InqPipeline.of(service, OrderApi.class)
          .shield(bh)
          .decorate();

      // When
      var r1 = resilient.processOrder("order-1");
      var r2 = resilient.processOrder("order-2");

      // Then
      assertThat(r1).isEqualTo("processed-order-1");
      assertThat(r2).isEqualTo("processed-order-2");
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
      assertThat(service.getCallCount()).isEqualTo(2);
    }

    @Test
    void should_create_a_typed_proxy_that_isolates_four_argument_calls() {
      // Given
      var service = new OrderService();
      var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
          .maxConcurrentCalls(5)
          .build());

      OrderApi resilient = InqPipeline.of(service, OrderApi.class)
          .shield(bh)
          .decorate();

      // When
      var r1 = resilient.processOrderDetailed("order-1", "EU", 1, true);
      var r2 = resilient.processOrderDetailed("order-2", "US", 3, false);

      // Then
      assertThat(r1).isEqualTo("order-1@EU-p1-expedited");
      assertThat(r2).isEqualTo("order-2@US-p3-normal");
      assertThat(bh.getAvailablePermits()).isEqualTo(5);
    }
  }

  // ── Event subscription ──

  @Nested
  @DisplayName("Event subscription")
  class Events {

    @Test
    void should_receive_acquire_and_release_events_on_successful_calls() {
      // Given
      var service = new OrderService();
      var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
          .maxConcurrentCalls(5)
          .build());
      var acquireEvents = new java.util.ArrayList<eu.inqudium.core.bulkhead.event.BulkheadOnAcquireEvent>();
      var releaseEvents = new java.util.ArrayList<eu.inqudium.core.bulkhead.event.BulkheadOnReleaseEvent>();

      bh.getEventPublisher().onEvent(
          eu.inqudium.core.bulkhead.event.BulkheadOnAcquireEvent.class,
          acquireEvents::add);
      bh.getEventPublisher().onEvent(
          eu.inqudium.core.bulkhead.event.BulkheadOnReleaseEvent.class,
          releaseEvents::add);

      Supplier<String> resilient = bh.decorateSupplier(() -> service.processOrder("order-1"));

      // When
      resilient.get();

      // Then — one acquire, one release
      assertThat(acquireEvents).hasSize(1);
      assertThat(releaseEvents).hasSize(1);
    }

    @Test
    void should_receive_reject_events_when_bulkhead_is_full() throws Exception {
      // Given
      var holdLatch = new CountDownLatch(1);
      var bh = Bulkhead.of("orderService", BulkheadConfig.builder()
          .maxConcurrentCalls(1)
          .build());
      var rejectEvents = new java.util.ArrayList<eu.inqudium.core.bulkhead.event.BulkheadOnRejectEvent>();

      bh.getEventPublisher().onEvent(
          eu.inqudium.core.bulkhead.event.BulkheadOnRejectEvent.class,
          rejectEvents::add);

      Supplier<String> slowCall = bh.decorateSupplier(() -> {
        try {
          holdLatch.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return "done";
      });
      Supplier<String> fastCall = bh.decorateSupplier(() -> "rejected");

      ExecutorService executor = Executors.newSingleThreadExecutor();
      executor.submit(slowCall::get);
      Thread.sleep(50);

      // When
      catchThrowable(fastCall::get);

      // Then
      assertThat(rejectEvents).hasSize(1);
      assertThat(rejectEvents.get(0).getConcurrentCalls()).isEqualTo(1);

      holdLatch.countDown();
      executor.shutdown();
    }
  }
}
