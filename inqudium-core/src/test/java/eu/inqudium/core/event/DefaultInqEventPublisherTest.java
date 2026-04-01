package eu.inqudium.core.event;

import eu.inqudium.core.element.InqElementType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultInqEventPublisherTest {

  // Helper event for testing without mocking
  static class TestEvent extends InqEvent {
    TestEvent() {
      super("call-1", "test-element", InqElementType.NO_ELEMENT, Instant.now());
    }
  }

  static class AnotherTestEvent extends InqEvent {
    AnotherTestEvent() {
      super("call-2", "test-element", InqElementType.NO_ELEMENT, Instant.now());
    }
  }

  @Nested
  class NullArguments {

    @Test
    void should_throw_exception_when_publishing_null_event() {
      // Given
      DefaultInqEventPublisher publisher = new DefaultInqEventPublisher(
          "test",
          InqElementType.NO_ELEMENT,
          new InqEventExporterRegistry(),
          InqPublisherConfig.defaultConfig()
      );

      // When & Then
      assertThatThrownBy(() -> publisher.publish(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("event must not be null");
    }

    @Test
    void should_throw_exception_when_registering_null_consumer() {
      // Given
      DefaultInqEventPublisher publisher = new DefaultInqEventPublisher(
          "test",
          InqElementType.NO_ELEMENT,
          new InqEventExporterRegistry(),
          InqPublisherConfig.defaultConfig()
      );

      // When & Then
      assertThatThrownBy(() -> publisher.onEvent(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("consumer must not be null");
    }

    @Test
    void should_throw_exception_when_registering_null_event_type_for_typed_consumer() {
      // Given
      DefaultInqEventPublisher publisher = new DefaultInqEventPublisher(
          "test",
          InqElementType.NO_ELEMENT,
          new InqEventExporterRegistry(),
          InqPublisherConfig.defaultConfig()
      );

      // When & Then
      assertThatThrownBy(() -> publisher.onEvent(null, event -> {
      }))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("eventType must not be null");
    }
  }

  @Nested
  class MultipleFatalErrors {

    @Test
    void should_collect_and_rethrow_only_the_first_fatal_error() {
      // Given
      DefaultInqEventPublisher publisher = new DefaultInqEventPublisher(
          "test",
          InqElementType.NO_ELEMENT,
          new InqEventExporterRegistry(),
          InqPublisherConfig.defaultConfig()
      );

      VirtualMachineError firstFatalError = new OutOfMemoryError("First fatal");
      LinkageError secondFatalError = new LinkageError("Second fatal");

      // Register first failing consumer
      publisher.onEvent(event -> {
        throw firstFatalError;
      });

      // Register second failing consumer
      publisher.onEvent(event -> {
        throw secondFatalError;
      });

      TestEvent event = new TestEvent();

      // When & Then
      // The publisher must throw the very first fatal error it encountered
      assertThatThrownBy(() -> publisher.publish(event))
          .isSameAs(firstFatalError);
    }

    @Test
    void should_handle_fatal_error_in_registry_export() {
      // Given
      InqEventExporterRegistry throwingRegistry = new InqEventExporterRegistry();
      ThreadDeath registryError = new ThreadDeath();

      throwingRegistry.register(event -> {
        throw registryError;
      });

      DefaultInqEventPublisher publisher = new DefaultInqEventPublisher(
          "test",
          InqElementType.NO_ELEMENT,
          throwingRegistry,
          InqPublisherConfig.defaultConfig()
      );

      TestEvent event = new TestEvent();

      // When & Then
      // The publisher forwards to the registry, catches the fatal error, and rethrows it at the end
      assertThatThrownBy(() -> publisher.publish(event))
          .isSameAs(registryError);
    }
  }

  @Nested
  class EventPublishing {

    @Test
    void should_deliver_events_to_both_local_consumers_and_global_registry() {
      // Given
      InqEventExporterRegistry registry = new InqEventExporterRegistry();
      List<InqEvent> registryReceivedEvents = new ArrayList<>();
      registry.register(registryReceivedEvents::add);

      DefaultInqEventPublisher publisher = new DefaultInqEventPublisher(
          "test",
          InqElementType.NO_ELEMENT,
          registry,
          InqPublisherConfig.defaultConfig()
      );

      List<InqEvent> consumerReceivedEvents = new ArrayList<>();
      publisher.onEvent(consumerReceivedEvents::add);

      TestEvent testEvent = new TestEvent();

      // When
      // This single call will notify both the local consumers and the global registry
      publisher.publish(testEvent);

      // Then
      assertThat(consumerReceivedEvents)
          .hasSize(1)
          .containsExactly(testEvent);

      // The registry should have automatically received the event from the publisher
      assertThat(registryReceivedEvents)
          .hasSize(1)
          .containsExactly(testEvent);
    }

    @Test
    void should_deliver_only_specific_events_to_typed_consumer() {
      // Given
      DefaultInqEventPublisher publisher = new DefaultInqEventPublisher(
          "test",
          InqElementType.NO_ELEMENT,
          new InqEventExporterRegistry(),
          InqPublisherConfig.defaultConfig()
      );

      List<TestEvent> specificEventsReceived = new ArrayList<>();
      publisher.onEvent(TestEvent.class, specificEventsReceived::add);

      TestEvent testEvent = new TestEvent();
      AnotherTestEvent anotherEvent = new AnotherTestEvent();

      // When
      publisher.publish(testEvent);
      publisher.publish(anotherEvent);

      // Then
      assertThat(specificEventsReceived)
          .hasSize(1)
          .containsExactly(testEvent);
    }
  }

  @Nested
  class ErrorHandling {

    @Test
    void should_fail_fast_fatal_errors_until_all_consumers_processed_event() {
      // Given
      DefaultInqEventPublisher publisher = new DefaultInqEventPublisher(
          "test",
          InqElementType.NO_ELEMENT,
          new InqEventExporterRegistry(),
          InqPublisherConfig.defaultConfig()
      );

      List<InqEvent> processedAfterError = new ArrayList<>();

      // This consumer throws a fatal error
      publisher.onEvent(event -> {
        throw new LinkageError("Fatal system failure");
      });

      // This consumer should still receive the event despite the previous error
      publisher.onEvent(processedAfterError::add);

      TestEvent testEvent = new TestEvent();

      // When & Then
      assertThatThrownBy(() -> publisher.publish(testEvent))
          .isInstanceOf(LinkageError.class)
          .hasMessageContaining("Fatal system failure");

      // Verify the second consumer still ran
      assertThat(processedAfterError).hasSize(0);
    }

    @Test
    void should_silently_swallow_non_fatal_exceptions_from_consumers() {
      // Given
      DefaultInqEventPublisher publisher = new DefaultInqEventPublisher(
          "test",
          InqElementType.NO_ELEMENT,
          new InqEventExporterRegistry(),
          InqPublisherConfig.defaultConfig()
      );

      List<InqEvent> processedEvents = new ArrayList<>();

      publisher.onEvent(event -> {
        throw new RuntimeException("Standard non-fatal exception");
      });
      publisher.onEvent(processedEvents::add);

      TestEvent testEvent = new TestEvent();

      // When
      publisher.publish(testEvent);

      // Then
      assertThat(processedEvents)
          .hasSize(1)
          .containsExactly(testEvent);
    }
  }

  @Nested
  class SubscriptionManagement {

    @Test
    void should_successfully_remove_consumer_when_cancelled() {
      // Given
      DefaultInqEventPublisher publisher = new DefaultInqEventPublisher(
          "test",
          InqElementType.NO_ELEMENT,
          new InqEventExporterRegistry(),
          InqPublisherConfig.defaultConfig()
      );

      List<InqEvent> receivedEvents = new ArrayList<>();
      InqSubscription subscription = publisher.onEvent(receivedEvents::add);

      TestEvent firstEvent = new TestEvent();
      TestEvent secondEvent = new TestEvent();

      // When
      publisher.publish(firstEvent);
      subscription.cancel();
      publisher.publish(secondEvent);

      // Then
      assertThat(receivedEvents)
          .hasSize(1)
          .containsExactly(firstEvent);
    }
  }
}