package eu.inqudium.core.event;

import eu.inqudium.core.InqElementType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InqEventExporterRegistryTest {

  static class TestEvent extends InqEvent {
    TestEvent() {
      super("call-1", "test-element", InqElementType.NO_ELEMENT, Instant.now());
    }
  }

  @Nested
  class ExporterEdgeCases {

    @Test
    void should_throw_exception_when_registering_null_exporter() {
      // Given
      InqEventExporterRegistry registry = new InqEventExporterRegistry();

      // When & Then
      assertThatThrownBy(() -> registry.register(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("exporter must not be null");
    }

    @Test
    void should_treat_exporter_as_subscribed_to_all_if_subscribed_event_types_returns_null() {
      // Given
      InqEventExporterRegistry registry = new InqEventExporterRegistry();
      List<InqEvent> receivedEvents = new ArrayList<>();

      InqEventExporter badExporter = new InqEventExporter() {
        @Override
        public void export(InqEvent event) {
          receivedEvents.add(event);
        }

        @Override
        public Set<Class<? extends InqEvent>> subscribedEventTypes() {
          // Edge case: explicitly returning null instead of empty set
          return null;
        }
      };

      registry.register(badExporter);
      TestEvent event = new TestEvent();

      // When
      // Forcing the registry to freeze and cache the event types (which evaluates the null return)
      registry.export(event);

      // Then
      // If it returns null, it falls back to receiving all events
      assertThat(receivedEvents)
          .hasSize(1)
          .containsExactly(event);
    }

    @Test
    void should_treat_exporter_as_subscribed_to_all_if_subscribed_event_types_throws_non_fatal_exception() {
      // Given
      InqEventExporterRegistry registry = new InqEventExporterRegistry();
      List<InqEvent> receivedEvents = new ArrayList<>();

      InqEventExporter throwingExporter = new InqEventExporter() {
        @Override
        public void export(InqEvent event) {
          receivedEvents.add(event);
        }

        @Override
        public Set<Class<? extends InqEvent>> subscribedEventTypes() {
          // Edge case: exporter throws an exception during resolution
          throw new RuntimeException("Unexpected error computing event types");
        }
      };

      registry.register(throwingExporter);
      TestEvent event = new TestEvent();

      // When
      registry.export(event);

      // Then
      // The registry catches the non-fatal error and falls back to passing all events to this exporter
      assertThat(receivedEvents)
          .hasSize(1)
          .containsExactly(event);
    }
  }

  @Nested
  class ExporterRegistration {

    @Test
    void should_accept_programmatic_registration_when_in_open_state() {
      // Given
      InqEventExporterRegistry registry = new InqEventExporterRegistry();
      List<InqEvent> exportedEvents = new ArrayList<>();

      InqEventExporter myExporter = exportedEvents::add;

      // When
      registry.register(myExporter);
      registry.export(new TestEvent());

      // Then
      assertThat(exportedEvents).hasSize(1);
    }

    @Test
    void should_throw_exception_if_registration_attempted_after_first_export() {
      // Given
      InqEventExporterRegistry registry = new InqEventExporterRegistry();

      // This forces the registry to transition from Open -> Resolving -> Frozen
      registry.export(new TestEvent());

      InqEventExporter lateExporter = event -> {
      };

      // When & Then
      assertThatThrownBy(() -> registry.register(lateExporter))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("InqEventExporterRegistry is frozen");
    }
  }

  @Nested
  class EventFiltering {

    @Test
    void should_only_receive_events_it_is_subscribed_to() {
      // Given
      InqEventExporterRegistry registry = new InqEventExporterRegistry();
      List<InqEvent> receivedEvents = new ArrayList<>();

      InqEventExporter filteredExporter = new InqEventExporter() {
        @Override
        public void export(InqEvent event) {
          receivedEvents.add(event);
        }

        @Override
        public Set<Class<? extends InqEvent>> subscribedEventTypes() {
          return Set.of(TestEvent.class);
        }
      };

      registry.register(filteredExporter);

      InqEvent relevantEvent = new TestEvent();
      InqEvent irrelevantEvent = new InqEvent("c-2", "el", InqElementType.NO_ELEMENT, Instant.now()) {
      };

      // When
      registry.export(relevantEvent);
      registry.export(irrelevantEvent);

      // Then
      assertThat(receivedEvents)
          .hasSize(1)
          .containsExactly(relevantEvent);
    }
  }

  @Nested
  class ErrorIsolation {

    @Test
    void should_not_prevent_other_exporters_from_receiving_event_on_exception() {
      // Given
      InqEventExporterRegistry registry = new InqEventExporterRegistry();

      List<InqEvent> successfulExports = new ArrayList<>();

      InqEventExporter failingExporter = event -> {
        throw new RuntimeException("Exporter failure");
      };
      InqEventExporter successfulExporter = successfulExports::add;

      registry.register(failingExporter);
      registry.register(successfulExporter);

      TestEvent event = new TestEvent();

      // When
      registry.export(event);

      // Then
      assertThat(successfulExports)
          .hasSize(1)
          .containsExactly(event);
    }
  }
}
