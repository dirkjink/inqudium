package eu.inqudium.imperative.trafficshaper;

import eu.inqudium.core.element.trafficshaper.TrafficShaperConfig;
import eu.inqudium.core.element.trafficshaper.TrafficShaperEvent;
import eu.inqudium.core.element.trafficshaper.TrafficShaperException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ImperativeTrafficShaper")
class ImperativeTrafficShaperTest {

  // ================================================================
  // Execution & Shaping
  // ================================================================

  @Nested
  @DisplayName("Execution & Shaping")
  class ExecutionAndShaping {

    @Test
    @DisplayName("should execute immediately when the queue is empty")
    void should_execute_immediately_when_the_queue_is_empty() throws Exception {
      // Given
      var config = TrafficShaperConfig.builder("immediate")
          .ratePerSecond(100)
          .build();
      var shaper = new ImperativeTrafficShaper<>(config);

      // When
      String result = shaper.execute(() -> "success");

      // Then
      assertThat(result).isEqualTo("success");
    }

    @Test
    @DisplayName("should rethrow runtime exceptions from the callable")
    void should_rethrow_runtime_exceptions_from_the_callable() {
      // Given
      var config = TrafficShaperConfig.builder("exceptions").ratePerSecond(100).build();
      var shaper = new ImperativeTrafficShaper<>(config);

      // When / Then
      assertThatThrownBy(() -> shaper.execute(() -> {
        throw new IllegalArgumentException("invalid argument");
      }))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("invalid argument");
    }

    @Test
    @DisplayName("should delay executions to match the configured rate")
    void should_delay_executions_to_match_the_configured_rate() throws Exception {
      // Given
      var config = TrafficShaperConfig.builder("delay")
          .ratePerSecond(10) // 100ms interval
          .build();
      var shaper = new ImperativeTrafficShaper<>(config);
      List<TrafficShaperEvent> events = new ArrayList<>();
      shaper.onEvent(events::add);

      // When
      shaper.execute(() -> "first");  // Slot: now
      shaper.execute(() -> "second"); // Slot: now + 100ms

      // Then — 3 events: ADMITTED_IMMEDIATE, ADMITTED_DELAYED, EXECUTING
      assertThat(events).hasSize(3);
      assertThat(events.get(0).type()).isEqualTo(TrafficShaperEvent.Type.ADMITTED_IMMEDIATE);
      assertThat(events.get(1).type()).isEqualTo(TrafficShaperEvent.Type.ADMITTED_DELAYED);
      assertThat(events.get(2).type()).isEqualTo(TrafficShaperEvent.Type.EXECUTING);
      // The wait duration of the second request should be around 100ms
      assertThat(events.get(1).waitDuration()).isCloseTo(Duration.ofMillis(100), Duration.ofMillis(20));
    }
  }

  // ================================================================
  // Overflow Handling (Rejections)
  // ================================================================

  @Nested
  @DisplayName("Overflow Handling")
  class OverflowHandling {

    @Test
    @DisplayName("should reject execution when the queue depth is exceeded")
    void should_reject_execution_when_the_queue_depth_is_exceeded() throws InterruptedException {
      // Given
      var config = TrafficShaperConfig.builder("overflow")
          .ratePerSecond(2) // 500ms interval
          .maxQueueDepth(2)
          .build();
      var shaper = new ImperativeTrafficShaper<>(config);
      AtomicInteger successCount = new AtomicInteger();
      AtomicInteger rejectCount = new AtomicInteger();
      CountDownLatch latch = new CountDownLatch(5);

      // When — submit 5 concurrent requests
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < 5; i++) {
          executor.submit(() -> {
            try {
              shaper.execute(() -> "ok");
              successCount.incrementAndGet();
            } catch (TrafficShaperException e) {
              rejectCount.incrementAndGet();
            } catch (Exception e) {
              // Ignore other exceptions for this test
            } finally {
              latch.countDown();
            }
          });
        }
        latch.await(5, TimeUnit.SECONDS);
      }

      // Then — 1 immediate + 2 queued = 3 successes. 2 must be rejected.
      assertThat(successCount.get()).isEqualTo(3);
      assertThat(rejectCount.get()).isEqualTo(2);
    }
  }

  // ================================================================
  // Fallbacks
  // ================================================================

  @Nested
  @DisplayName("Fallbacks")
  class Fallbacks {

    @Test
    @DisplayName("should use fallback when the traffic shaper rejects the request")
    void should_use_fallback_when_the_traffic_shaper_rejects_the_request() throws Exception {
      // Given
      var config = TrafficShaperConfig.builder("fallback")
          .ratePerSecond(1) // 1 slot per second
          .maxWaitDuration(Duration.ofMillis(10)) // reject if wait > 10ms
          .build();
      var shaper = new ImperativeTrafficShaper<>(config);

      // 1st request takes the slot (next free slot moves 1 second into the future)
      shaper.execute(() -> "ok");

      // When — 2nd request would wait ~1000ms > 10ms → REJECTED → fallback
      String result = shaper.executeWithFallback(
          () -> "primary",
          () -> "fallback-value"
      );

      // Then
      assertThat(result).isEqualTo("fallback-value");
    }

    @Test
    @DisplayName("should not mask exceptions from inner nested traffic shapers")
    void should_not_mask_exceptions_from_inner_nested_traffic_shapers() throws Exception {
      // Given
      var outerConfig = TrafficShaperConfig.builder("outer").ratePerSecond(100).build();
      var innerConfig = TrafficShaperConfig.builder("inner")
          .ratePerSecond(1)
          .maxWaitDuration(Duration.ofMillis(10)) // forces rejection for waiting requests
          .build();
      var outer = new ImperativeTrafficShaper<>(outerConfig);
      var inner = new ImperativeTrafficShaper<>(innerConfig);

      // Exhaust the inner shaper (next slot moves 1 second into the future)
      inner.execute(() -> "ok");

      // When / Then — the outer fallback must NOT catch the inner shaper's exception
      assertThatThrownBy(() -> outer.executeWithFallback(
          () -> inner.execute(() -> "fail"),
          () -> "outer-fallback"
      ))
          .isInstanceOf(TrafficShaperException.class)
          .satisfies(e -> {
            TrafficShaperException ex = (TrafficShaperException) e;
            assertThat(ex.getTrafficShaperName()).isEqualTo("inner");
          });
    }
  }

  // ================================================================
  // Introspection & Events
  // ================================================================

  @Nested
  @DisplayName("Introspection and Events")
  class IntrospectionAndEvents {

    @Test
    @DisplayName("should emit the correct sequence of events")
    void should_emit_the_correct_sequence_of_events() throws Exception {
      // Given
      var config = TrafficShaperConfig.builder("events")
          .ratePerSecond(10)
          .build();
      var shaper = new ImperativeTrafficShaper<>(config);
      List<TrafficShaperEvent.Type> eventTypes = new ArrayList<>();
      shaper.onEvent(e -> eventTypes.add(e.type()));

      // When
      shaper.execute(() -> "immediate");
      shaper.execute(() -> "delayed");

      // Then — immediate requests skip the queue entirely (no EXECUTING event),
      // only delayed requests emit EXECUTING after their wait completes
      assertThat(eventTypes).containsExactly(
          TrafficShaperEvent.Type.ADMITTED_IMMEDIATE,
          TrafficShaperEvent.Type.ADMITTED_DELAYED,
          TrafficShaperEvent.Type.EXECUTING
      );
    }

    @Test
    @DisplayName("should return the configuration")
    void should_return_the_configuration() {
      // Given
      var config = TrafficShaperConfig.builder("config").ratePerSecond(5).build();
      var shaper = new ImperativeTrafficShaper<>(config);

      // When / Then
      assertThat(shaper.getConfig()).isEqualTo(config);
      assertThat(shaper.getConfig().name()).isEqualTo("config");
    }
  }
}
