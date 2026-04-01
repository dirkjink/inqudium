package eu.inqudium.core.event;

import eu.inqudium.core.element.InqElementType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InqEventConcurrencyAndInfrastructureTest {

  static class TestEvent extends InqEvent {
    TestEvent() {
      super("concurrent-call", "test-element", InqElementType.NO_ELEMENT, Instant.now());
    }
  }

  @Nested
  class RegistryConcurrency {

    @Test
    void should_safely_handle_concurrent_exports_without_corrupting_state_or_losing_events() throws InterruptedException {
      // Given
      InqEventExporterRegistry registry = new InqEventExporterRegistry();

      // Using a thread-safe list to collect events from multiple threads
      List<InqEvent> receivedEvents = Collections.synchronizedList(new ArrayList<>());
      AtomicInteger executionCounter = new AtomicInteger(0);

      registry.register(event -> {
        receivedEvents.add(event);
        executionCounter.incrementAndGet();
      });

      int numberOfThreads = 50;
      ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

      // When
      for (int i = 0; i < numberOfThreads; i++) {
        executor.submit(() -> {
          try {
            // Wait until all threads are ready to fire simultaneously
            startLatch.await();

            // All threads export an event at the exact same time.
            // This forces multiple threads into the registry's lock-free state machine.
            // Only one should transition Open -> Resolving, others must park and wait.
            registry.export(new TestEvent());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            doneLatch.countDown();
          }
        });
      }

      // Release the hounds - let all threads run at once
      startLatch.countDown();

      // Wait for all threads to finish (with a safe timeout to prevent infinite hangs)
      boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
      executor.shutdown();

      // Then
      assertThat(completed).as("All threads should have finished without deadlocking").isTrue();

      // We fired 50 events across 50 threads, so our exporter should have seen exactly 50 events
      assertThat(executionCounter.get()).isEqualTo(numberOfThreads);
      assertThat(receivedEvents).hasSize(numberOfThreads);
    }
  }

  @Nested
  class GlobalRegistryInstance {

    @Test
    void should_always_return_the_same_default_instance_when_called_multiple_times() {
      // Given & When
      InqEventExporterRegistry firstCall = InqEventExporterRegistry.getDefault();
      InqEventExporterRegistry secondCall = InqEventExporterRegistry.getDefault();

      // Then
      assertThat(firstCall).isNotNull();
      assertThat(firstCall).isSameAs(secondCall);
    }

    @Test
    void should_allow_overriding_the_default_instance_with_a_custom_one() {
      // Given
      InqEventExporterRegistry originalDefault = InqEventExporterRegistry.getDefault();
      InqEventExporterRegistry customRegistry = new InqEventExporterRegistry();

      try {
        // When
        InqEventExporterRegistry.setDefault(customRegistry);

        // Then
        assertThat(InqEventExporterRegistry.getDefault()).isSameAs(customRegistry);
        assertThat(InqEventExporterRegistry.getDefault()).isNotSameAs(originalDefault);
      } finally {
        // Cleanup: Restore original state so we don't pollute other tests sharing the JVM
        InqEventExporterRegistry.setDefault(originalDefault);
      }
    }
  }

  @Nested
  class ObjectUtilityMethods {

    @Test
    void should_generate_meaningful_to_string_for_publisher() {
      // Given
      InqEventExporterRegistry registry = new InqEventExporterRegistry();
      String expectedElementName = "my-special-breaker";
      DefaultInqEventPublisher publisher = new DefaultInqEventPublisher(
          expectedElementName,
          InqElementType.NO_ELEMENT,
          registry,
          InqPublisherConfig.defaultConfig()
      );

      // Add a consumer to alter the 'consumers' count in the toString output
      publisher.onEvent(event -> {
      });

      // When
      String result = publisher.toString();

      // Then
      assertThat(result)
          .contains("InqEventPublisher")
          .contains("elementName='" + expectedElementName + "'")
          .contains("elementType=" + InqElementType.NO_ELEMENT)
          // We registered exactly 1 consumer
          .contains("consumers=1");
    }

    @Test
    void should_generate_meaningful_to_string_for_event() {
      // Given
      String callId = "correlation-99";
      String elementName = "retry-element";
      Instant timestamp = Instant.EPOCH;

      InqEvent event = new InqEvent(callId, elementName, InqElementType.NO_ELEMENT, timestamp) {
        // Anonymous concrete implementation
      };

      // When
      String result = event.toString();

      // Then
      assertThat(result)
          .contains("callId='" + callId + "'")
          .contains("elementName='" + elementName + "'")
          .contains("elementType=" + InqElementType.NO_ELEMENT)
          .contains("timestamp=" + timestamp);
    }
  }
}