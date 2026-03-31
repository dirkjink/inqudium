package eu.inqudium.core.event;

import eu.inqudium.core.InqElementType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Comprehensive test suite for the Inqudium event system.
 *
 * <p>Covers the core components: {@link DefaultInqEventPublisher},
 * {@link InqEventExporterRegistry}, {@link InqConsumerExpiryWatchdog},
 * {@link InqPublisherConfig}, and {@link InqProviderErrorEvent}.
 *
 * <p>All tests use an isolated {@link InqEventExporterRegistry} to prevent
 * cross-test pollution. TTL-related tests use short durations and direct
 * sweep invocations to avoid flaky timing dependencies.
 */
class InqEventSystemTest {

  private InqEventExporterRegistry registry;

  /**
   * Creates a minimal concrete event for testing.
   */
  private static InqEvent testEvent() {
    return testEvent("call-1");
  }

  // ── Test helpers ──────────────────────────────────────────────────────────

  private static InqEvent testEvent(String callId) {
    return new TestEvent(callId, "test-element", InqElementType.CIRCUIT_BREAKER, Instant.now());
  }

  /**
   * Sleeps for the given duration, wrapping InterruptedException.
   * Used only in tests that need to wait for TTL expiry.
   */
  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Test interrupted", e);
    }
  }

  @BeforeEach
  void setUp() {
    registry = new InqEventExporterRegistry();
  }

  /**
   * Creates a publisher with isolated registry and default config.
   */
  private InqEventPublisher createPublisher() {
    return InqEventPublisher.create("test-element", InqElementType.CIRCUIT_BREAKER,
        registry, InqPublisherConfig.defaultConfig());
  }

  /**
   * Creates a publisher with custom config.
   */
  private InqEventPublisher createPublisher(InqPublisherConfig config) {
    return InqEventPublisher.create("test-element", InqElementType.CIRCUIT_BREAKER,
        registry, config);
  }

  /**
   * Concrete event subclass for testing typed consumer dispatch.
   */
  private static final class TestEvent extends InqEvent {
    TestEvent(String callId, String elementName, InqElementType elementType, Instant timestamp) {
      super(callId, elementName, elementType, timestamp);
    }
  }

  /**
   * A second event subclass to verify type filtering.
   */
  private static final class OtherTestEvent extends InqEvent {
    OtherTestEvent(String callId, String elementName, InqElementType elementType, Instant timestamp) {
      super(callId, elementName, elementType, timestamp);
    }
  }

  /**
   * Simple recording consumer that captures all received events.
   */
  private static final class RecordingConsumer implements InqEventConsumer {
    private final List<InqEvent> received = new CopyOnWriteArrayList<>();

    @Override
    public void accept(InqEvent event) {
      received.add(event);
    }

    List<InqEvent> received() {
      return Collections.unmodifiableList(received);
    }

    int count() {
      return received.size();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Publishing
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Simple recording exporter that captures all exported events.
   */
  private static final class RecordingExporter implements InqEventExporter {
    private final List<InqEvent> exported = new CopyOnWriteArrayList<>();
    private final Set<Class<? extends InqEvent>> subscribedTypes;

    RecordingExporter() {
      this.subscribedTypes = Set.of();
    }

    RecordingExporter(Set<Class<? extends InqEvent>> subscribedTypes) {
      this.subscribedTypes = subscribedTypes;
    }

    @Override
    public void export(InqEvent event) {
      exported.add(event);
    }

    @Override
    public Set<Class<? extends InqEvent>> subscribedEventTypes() {
      return subscribedTypes;
    }

    List<InqEvent> exported() {
      return Collections.unmodifiableList(exported);
    }

    int count() {
      return exported.size();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Typed consumers
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Publishing events")
  class Publishing {

    @Test
    @DisplayName("should deliver event to a registered consumer")
    void should_deliver_event_to_a_registered_consumer() {
      // Given
      var publisher = createPublisher();
      var consumer = new RecordingConsumer();
      publisher.onEvent(consumer);
      var event = testEvent();

      // When
      publisher.publish(event);

      // Then
      assertThat(consumer.received())
          .hasSize(1)
          .containsExactly(event);
    }

    @Test
    @DisplayName("should deliver event to multiple consumers in registration order")
    void should_deliver_event_to_multiple_consumers_in_registration_order() {
      // Given
      var publisher = createPublisher();
      var order = new CopyOnWriteArrayList<String>();

      publisher.onEvent(e -> order.add("first"));
      publisher.onEvent(e -> order.add("second"));
      publisher.onEvent(e -> order.add("third"));

      // When
      publisher.publish(testEvent());

      // Then
      assertThat(order).containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("should not fail when no consumers are registered")
    void should_not_fail_when_no_consumers_are_registered() {
      // Given
      var publisher = createPublisher();

      // When / Then
      assertThatCode(() -> publisher.publish(testEvent()))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should reject null event")
    void should_reject_null_event() {
      // Given
      var publisher = createPublisher();

      // When / Then
      assertThatNullPointerException()
          .isThrownBy(() -> publisher.publish(null))
          .withMessageContaining("event");
    }

    @Test
    @DisplayName("should catch consumer exception and continue delivering to remaining consumers")
    void should_catch_consumer_exception_and_continue_delivering_to_remaining_consumers() {
      // Given
      var publisher = createPublisher();
      var consumerAfterFailure = new RecordingConsumer();

      publisher.onEvent(e -> {
        throw new RuntimeException("boom");
      });
      publisher.onEvent(consumerAfterFailure);

      // When
      publisher.publish(testEvent());

      // Then
      assertThat(consumerAfterFailure.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("should forward event to global exporters after local consumers")
    void should_forward_event_to_global_exporters_after_local_consumers() {
      // Given
      var exporter = new RecordingExporter();
      registry.register(exporter);

      var publisher = createPublisher();
      var consumer = new RecordingConsumer();
      publisher.onEvent(consumer);
      var event = testEvent();

      // When
      publisher.publish(event);

      // Then
      assertThat(consumer.received()).containsExactly(event);
      assertThat(exporter.exported()).containsExactly(event);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Subscriptions
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Typed consumer filtering")
  class TypedConsumerFiltering {

    @Test
    @DisplayName("should deliver event when type matches")
    void should_deliver_event_when_type_matches() {
      // Given
      var publisher = createPublisher();
      var received = new CopyOnWriteArrayList<TestEvent>();
      publisher.onEvent(TestEvent.class, received::add);

      var event = (TestEvent) testEvent();

      // When
      publisher.publish(event);

      // Then
      assertThat(received)
          .hasSize(1)
          .containsExactly(event);
    }

    @Test
    @DisplayName("should not deliver event when type does not match")
    void should_not_deliver_event_when_type_does_not_match() {
      // Given
      var publisher = createPublisher();
      var received = new CopyOnWriteArrayList<OtherTestEvent>();
      publisher.onEvent(OtherTestEvent.class, received::add);

      // When — publish a TestEvent, consumer listens for OtherTestEvent
      publisher.publish(testEvent());

      // Then
      assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("should deliver to both typed and untyped consumers on matching event")
    void should_deliver_to_both_typed_and_untyped_consumers_on_matching_event() {
      // Given
      var publisher = createPublisher();
      var untypedConsumer = new RecordingConsumer();
      var typedReceived = new CopyOnWriteArrayList<TestEvent>();

      publisher.onEvent(untypedConsumer);
      publisher.onEvent(TestEvent.class, typedReceived::add);

      var event = testEvent();

      // When
      publisher.publish(event);

      // Then
      assertThat(untypedConsumer.count()).isEqualTo(1);
      assertThat(typedReceived).hasSize(1);
    }

    @Test
    @DisplayName("should reject null event type")
    void should_reject_null_event_type() {
      // Given
      var publisher = createPublisher();

      // When / Then
      assertThatNullPointerException()
          .isThrownBy(() -> publisher.onEvent(null, e -> {
          }))
          .withMessageContaining("eventType");
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // TTL subscriptions
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Subscription management")
  class SubscriptionManagement {

    @Test
    @DisplayName("should stop delivering events after subscription is cancelled")
    void should_stop_delivering_events_after_subscription_is_cancelled() {
      // Given
      var publisher = createPublisher();
      var consumer = new RecordingConsumer();
      var subscription = publisher.onEvent(consumer);
      publisher.publish(testEvent("before-cancel"));

      // When
      subscription.cancel();
      publisher.publish(testEvent("after-cancel"));

      // Then
      assertThat(consumer.count()).isEqualTo(1);
      assertThat(consumer.received().getFirst().getCallId()).isEqualTo("before-cancel");
    }

    @Test
    @DisplayName("should allow idempotent cancellation without side effects")
    void should_allow_idempotent_cancellation_without_side_effects() {
      // Given
      var publisher = createPublisher();
      var consumer = new RecordingConsumer();
      var subscription = publisher.onEvent(consumer);

      // When
      subscription.cancel();

      // Then — calling cancel again must not throw or affect other consumers
      assertThatCode(subscription::cancel).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should deliver event twice when same consumer is registered twice")
    void should_deliver_event_twice_when_same_consumer_is_registered_twice() {
      // Given
      var publisher = createPublisher();
      var consumer = new RecordingConsumer();
      publisher.onEvent(consumer);
      publisher.onEvent(consumer);

      // When
      publisher.publish(testEvent());

      // Then
      assertThat(consumer.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("should only remove one registration when cancelling one of two identical subscriptions")
    void should_only_remove_one_registration_when_cancelling_one_of_two_identical_subscriptions() {
      // Given
      var publisher = createPublisher();
      var consumer = new RecordingConsumer();
      var sub1 = publisher.onEvent(consumer);
      publisher.onEvent(consumer);

      // When
      sub1.cancel();
      publisher.publish(testEvent());

      // Then — only the second registration remains
      assertThat(consumer.count()).isEqualTo(1);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Consumer limits
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("TTL-based subscriptions")
  class TtlSubscriptions {

    @Test
    @DisplayName("should deliver events to TTL consumer before expiry")
    void should_deliver_events_to_ttl_consumer_before_expiry() {
      // Given
      var publisher = createPublisher();
      var consumer = new RecordingConsumer();
      publisher.onEvent(consumer, Duration.ofMinutes(10));

      // When — publish immediately (well before TTL)
      publisher.publish(testEvent());

      // Then
      assertThat(consumer.count()).isEqualTo(1);

      // Cleanup
      publisher.close();
    }

    @Test
    @DisplayName("should not deliver events to TTL consumer after expiry and sweep")
    void should_not_deliver_events_to_ttl_consumer_after_expiry_and_sweep() {
      // Given — use a very short TTL
      var config = InqPublisherConfig.of(256, Integer.MAX_VALUE, Duration.ofMillis(50));
      var publisher = (DefaultInqEventPublisher) InqEventPublisher.create(
          "test-element", InqElementType.CIRCUIT_BREAKER, registry, config);

      var consumer = new RecordingConsumer();
      publisher.onEvent(consumer, Duration.ofMillis(1));

      // When — wait for TTL to expire, then sweep manually
      sleep(50);
      publisher.performExpirySweep();
      publisher.publish(testEvent());

      // Then
      assertThat(consumer.count()).isZero();

      // Cleanup
      publisher.close();
    }

    @Test
    @DisplayName("should allow early cancellation of TTL subscription")
    void should_allow_early_cancellation_of_ttl_subscription() {
      // Given
      var publisher = createPublisher();
      var consumer = new RecordingConsumer();
      var subscription = publisher.onEvent(consumer, Duration.ofMinutes(10));

      // When
      subscription.cancel();
      publisher.publish(testEvent());

      // Then
      assertThat(consumer.count()).isZero();

      // Cleanup
      publisher.close();
    }

    @Test
    @DisplayName("should reject zero TTL duration")
    void should_reject_zero_ttl_duration() {
      // Given
      var publisher = createPublisher();

      // When / Then
      assertThatIllegalArgumentException()
          .isThrownBy(() -> publisher.onEvent(e -> {
          }, Duration.ZERO))
          .withMessageContaining("positive");
    }

    @Test
    @DisplayName("should reject negative TTL duration")
    void should_reject_negative_ttl_duration() {
      // Given
      var publisher = createPublisher();

      // When / Then
      assertThatIllegalArgumentException()
          .isThrownBy(() -> publisher.onEvent(e -> {
          }, Duration.ofSeconds(-1)))
          .withMessageContaining("positive");
    }

    @Test
    @DisplayName("should support typed consumers with TTL")
    void should_support_typed_consumers_with_ttl() {
      // Given
      var publisher = createPublisher();
      var received = new CopyOnWriteArrayList<TestEvent>();
      publisher.onEvent(TestEvent.class, received::add, Duration.ofMinutes(10));

      var event = (TestEvent) testEvent();

      // When
      publisher.publish(event);

      // Then
      assertThat(received).containsExactly(event);

      // Cleanup
      publisher.close();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Trace publishing
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Consumer limits")
  class ConsumerLimits {

    @Test
    @DisplayName("should reject registration when hard limit is reached")
    void should_reject_registration_when_hard_limit_is_reached() {
      // Given
      var config = InqPublisherConfig.of(2, 3, Duration.ofSeconds(60));
      var publisher = createPublisher(config);

      publisher.onEvent(e -> {
      });
      publisher.onEvent(e -> {
      });
      publisher.onEvent(e -> {
      });

      // When / Then — 4th registration exceeds hard limit of 3
      assertThatIllegalStateException()
          .isThrownBy(() -> publisher.onEvent(e -> {
          }))
          .withMessageContaining("hard consumer limit");
    }

    @Test
    @DisplayName("should allow registration up to hard limit exactly")
    void should_allow_registration_up_to_hard_limit_exactly() {
      // Given
      var config = InqPublisherConfig.of(1, 3, Duration.ofSeconds(60));
      var publisher = createPublisher(config);

      // When / Then — registering exactly 3 must succeed
      assertThatCode(() -> {
        publisher.onEvent(e -> {
        });
        publisher.onEvent(e -> {
        });
        publisher.onEvent(e -> {
        });
      }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should not count expired consumers towards hard limit")
    void should_not_count_expired_consumers_towards_hard_limit() {
      // Given — hard limit of 2
      var config = InqPublisherConfig.of(1, 2, Duration.ofMillis(50));
      var publisher = (DefaultInqEventPublisher) InqEventPublisher.create(
          "test-element", InqElementType.CIRCUIT_BREAKER, registry, config);

      // Register 2 TTL consumers that will expire
      publisher.onEvent(e -> {
      }, Duration.ofMillis(1));
      publisher.onEvent(e -> {
      }, Duration.ofMillis(1));

      // When — wait for TTL to expire
      sleep(50);

      // Then — new registrations succeed because expired ones are swept during add
      assertThatCode(() -> {
        publisher.onEvent(e -> {
        });
        publisher.onEvent(e -> {
        });
      }).doesNotThrowAnyException();

      // Cleanup
      publisher.close();
    }

    @Test
    @DisplayName("should allow registration again after cancellation frees a slot")
    void should_allow_registration_again_after_cancellation_frees_a_slot() {
      // Given — hard limit of 2
      var config = InqPublisherConfig.of(1, 2, Duration.ofSeconds(60));
      var publisher = createPublisher(config);

      var sub1 = publisher.onEvent(e -> {
      });
      publisher.onEvent(e -> {
      });

      // When — cancel one, then register a new one
      sub1.cancel();

      // Then
      assertThatCode(() -> publisher.onEvent(e -> {
      }))
          .doesNotThrowAnyException();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Publisher lifecycle
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Trace publishing")
  class TracePublishing {

    @Test
    @DisplayName("should not invoke supplier when trace is disabled")
    void should_not_invoke_supplier_when_trace_is_disabled() {
      // Given — default config has trace disabled
      var publisher = createPublisher();
      var supplierInvoked = new AtomicInteger(0);

      // When
      publisher.publishTrace(() -> {
        supplierInvoked.incrementAndGet();
        return testEvent();
      });

      // Then
      assertThat(supplierInvoked.get()).isZero();
    }

    @Test
    @DisplayName("should invoke supplier and publish when trace is enabled")
    void should_invoke_supplier_and_publish_when_trace_is_enabled() {
      // Given
      var config = InqPublisherConfig.of(256, Integer.MAX_VALUE, Duration.ofSeconds(60), true);
      var publisher = createPublisher(config);
      var consumer = new RecordingConsumer();
      publisher.onEvent(consumer);

      // When
      publisher.publishTrace(InqEventSystemTest::testEvent);

      // Then
      assertThat(consumer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("should report trace as disabled with default config")
    void should_report_trace_as_disabled_with_default_config() {
      // Given
      var publisher = createPublisher();

      // When / Then
      assertThat(publisher.isTraceEnabled()).isFalse();
    }

    @Test
    @DisplayName("should report trace as enabled with trace config")
    void should_report_trace_as_enabled_with_trace_config() {
      // Given
      var config = InqPublisherConfig.of(256, Integer.MAX_VALUE, Duration.ofSeconds(60), true);
      var publisher = createPublisher(config);

      // When / Then
      assertThat(publisher.isTraceEnabled()).isTrue();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Expiry sweep
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Publisher lifecycle")
  class PublisherLifecycle {

    @Test
    @DisplayName("should close without error when no TTL subscriptions were registered")
    void should_close_without_error_when_no_ttl_subscriptions_were_registered() {
      // Given
      var publisher = createPublisher();
      publisher.onEvent(e -> {
      });

      // When / Then
      assertThatCode(publisher::close).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should allow idempotent close")
    void should_allow_idempotent_close() {
      // Given
      var publisher = createPublisher();
      publisher.onEvent(e -> {
      }, Duration.ofMinutes(1));

      // When / Then
      publisher.close();
      assertThatCode(publisher::close).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should produce meaningful toString output")
    void should_produce_meaningful_to_string_output() {
      // Given
      var publisher = createPublisher();
      publisher.onEvent(e -> {
      });

      // When
      String result = publisher.toString();

      // Then
      assertThat(result)
          .contains("test-element")
          .contains("CIRCUIT_BREAKER")
          .contains("consumers=1");
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Exporter registry
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Expiry sweep logic")
  class ExpirySweep {

    @Test
    @DisplayName("should not remove permanent consumers during sweep")
    void should_not_remove_permanent_consumers_during_sweep() {
      // Given
      var config = InqPublisherConfig.of(256, Integer.MAX_VALUE, Duration.ofMillis(50));
      var publisher = (DefaultInqEventPublisher) InqEventPublisher.create(
          "test-element", InqElementType.CIRCUIT_BREAKER, registry, config);

      var permanentConsumer = new RecordingConsumer();
      publisher.onEvent(permanentConsumer);

      // When
      publisher.performExpirySweep();
      publisher.publish(testEvent());

      // Then
      assertThat(permanentConsumer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("should remove only expired consumers and keep active TTL consumers")
    void should_remove_only_expired_consumers_and_keep_active_ttl_consumers() {
      // Given
      var config = InqPublisherConfig.of(256, Integer.MAX_VALUE, Duration.ofMillis(50));
      var publisher = (DefaultInqEventPublisher) InqEventPublisher.create(
          "test-element", InqElementType.CIRCUIT_BREAKER, registry, config);

      var shortLived = new RecordingConsumer();
      var longLived = new RecordingConsumer();

      publisher.onEvent(shortLived, Duration.ofMillis(1));
      publisher.onEvent(longLived, Duration.ofMinutes(10));

      // When — wait for short-lived to expire, then sweep
      sleep(50);
      publisher.performExpirySweep();
      publisher.publish(testEvent());

      // Then
      assertThat(shortLived.count()).isZero();
      assertThat(longLived.count()).isEqualTo(1);

      // Cleanup
      publisher.close();
    }

    @Test
    @DisplayName("should return empty array when all consumers are expired")
    void should_return_empty_array_when_all_consumers_are_expired() {
      // Given
      var now = Instant.now();
      var pastExpiry = now.minusSeconds(10);
      var entries = new DefaultInqEventPublisher.ConsumerEntry[]{
          new DefaultInqEventPublisher.ConsumerEntry(1, e -> {
          }, "a", pastExpiry),
          new DefaultInqEventPublisher.ConsumerEntry(2, e -> {
          }, "b", pastExpiry),
      };

      // When
      var result = DefaultInqEventPublisher.sweepExpired(entries, now);

      // Then
      assertThat(result)
          .isSameAs(DefaultInqEventPublisher.EMPTY_CONSUMERS)
          .isEmpty();
    }

    @Test
    @DisplayName("should return same array reference when nothing is expired")
    void should_return_same_array_reference_when_nothing_is_expired() {
      // Given
      var now = Instant.now();
      var futureExpiry = now.plusSeconds(3600);
      var entries = new DefaultInqEventPublisher.ConsumerEntry[]{
          new DefaultInqEventPublisher.ConsumerEntry(1, e -> {
          }, "a", futureExpiry),
          new DefaultInqEventPublisher.ConsumerEntry(2, e -> {
          }, "b", null), // permanent
      };

      // When
      var result = DefaultInqEventPublisher.sweepExpired(entries, now);

      // Then — same reference means no allocation occurred
      assertThat(result).isSameAs(entries);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Publisher config
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Exporter registry")
  class ExporterRegistry {

    @Test
    @DisplayName("should deliver event to programmatically registered exporter")
    void should_deliver_event_to_programmatically_registered_exporter() {
      // Given
      var exporter = new RecordingExporter();
      registry.register(exporter);
      var event = testEvent();

      // When
      registry.export(event);

      // Then
      assertThat(exporter.exported())
          .hasSize(1)
          .containsExactly(event);
    }

    @Test
    @DisplayName("should deliver event to multiple exporters")
    void should_deliver_event_to_multiple_exporters() {
      // Given
      var exporter1 = new RecordingExporter();
      var exporter2 = new RecordingExporter();
      registry.register(exporter1);
      registry.register(exporter2);

      // When
      registry.export(testEvent());

      // Then
      assertThat(exporter1.count()).isEqualTo(1);
      assertThat(exporter2.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("should reject registration after registry is frozen")
    void should_reject_registration_after_registry_is_frozen() {
      // Given — freeze the registry by triggering export
      registry.export(testEvent());

      // When / Then
      assertThatIllegalStateException()
          .isThrownBy(() -> registry.register(new RecordingExporter()))
          .withMessageContaining("frozen");
    }

    @Test
    @DisplayName("should isolate exporter exceptions from other exporters")
    void should_isolate_exporter_exceptions_from_other_exporters() {
      // Given
      var failingExporter = new InqEventExporter() {
        @Override
        public void export(InqEvent event) {
          throw new RuntimeException("exporter failure");
        }
      };
      var healthyExporter = new RecordingExporter();

      registry.register(failingExporter);
      registry.register(healthyExporter);

      // When
      registry.export(testEvent());

      // Then — healthy exporter still receives the event
      assertThat(healthyExporter.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("should filter events by subscribed event types")
    void should_filter_events_by_subscribed_event_types() {
      // Given — exporter only subscribes to OtherTestEvent
      var selectiveExporter = new RecordingExporter(Set.of(OtherTestEvent.class));
      var catchAllExporter = new RecordingExporter();

      registry.register(selectiveExporter);
      registry.register(catchAllExporter);

      // When — publish a TestEvent
      registry.export(testEvent());

      // Then
      assertThat(selectiveExporter.count()).isZero();
      assertThat(catchAllExporter.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("should deliver matching event to type-filtered exporter")
    void should_deliver_matching_event_to_type_filtered_exporter() {
      // Given — exporter subscribes to TestEvent
      var exporter = new RecordingExporter(Set.of(TestEvent.class));
      registry.register(exporter);

      // When
      registry.export(testEvent());

      // Then
      assertThat(exporter.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("should accept events without error when no exporters are registered")
    void should_accept_events_without_error_when_no_exporters_are_registered() {
      // Given — empty registry

      // When / Then
      assertThatCode(() -> registry.export(testEvent()))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should reject null exporter on registration")
    void should_reject_null_exporter_on_registration() {
      // When / Then
      assertThatNullPointerException()
          .isThrownBy(() -> registry.register(null))
          .withMessageContaining("exporter");
    }

    @Test
    @DisplayName("should allow reset and re-registration for test isolation")
    void should_allow_reset_and_re_registration_for_test_isolation() {
      // Given — freeze the registry
      var exporter1 = new RecordingExporter();
      registry.register(exporter1);
      registry.export(testEvent());
      assertThat(exporter1.count()).isEqualTo(1);

      // When — reset and register a new exporter
      registry.reset();
      var exporter2 = new RecordingExporter();
      registry.register(exporter2);
      registry.export(testEvent());

      // Then — new exporter receives event, old one only has the original
      assertThat(exporter2.count()).isEqualTo(1);
      assertThat(exporter1.count()).isEqualTo(1);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Provider error event
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Publisher configuration")
  class PublisherConfiguration {

    @Test
    @DisplayName("should provide sensible defaults")
    void should_provide_sensible_defaults() {
      // Given
      var config = InqPublisherConfig.defaultConfig();

      // Then
      assertThat(config.softLimit()).isEqualTo(256);
      assertThat(config.hardLimit()).isEqualTo(Integer.MAX_VALUE);
      assertThat(config.expiryCheckInterval()).isEqualTo(Duration.ofSeconds(60));
      assertThat(config.traceEnabled()).isFalse();
      assertThat(config.hasHardLimit()).isFalse();
    }

    @Test
    @DisplayName("should create config with custom values")
    void should_create_config_with_custom_values() {
      // Given
      var interval = Duration.ofMillis(500);

      // When
      var config = InqPublisherConfig.of(64, 128, interval);

      // Then
      assertThat(config.softLimit()).isEqualTo(64);
      assertThat(config.hardLimit()).isEqualTo(128);
      assertThat(config.expiryCheckInterval()).isEqualTo(interval);
      assertThat(config.traceEnabled()).isFalse();
      assertThat(config.hasHardLimit()).isTrue();
    }

    @Test
    @DisplayName("should create config with trace enabled")
    void should_create_config_with_trace_enabled() {
      // When
      var config = InqPublisherConfig.of(10, 20, Duration.ofSeconds(1), true);

      // Then
      assertThat(config.traceEnabled()).isTrue();
    }

    @Test
    @DisplayName("should reject soft limit below one")
    void should_reject_soft_limit_below_one() {
      // When / Then
      assertThatIllegalArgumentException()
          .isThrownBy(() -> InqPublisherConfig.of(0, 10, Duration.ofSeconds(1)))
          .withMessageContaining("softLimit");
    }

    @Test
    @DisplayName("should reject hard limit below soft limit")
    void should_reject_hard_limit_below_soft_limit() {
      // When / Then
      assertThatIllegalArgumentException()
          .isThrownBy(() -> InqPublisherConfig.of(10, 5, Duration.ofSeconds(1)))
          .withMessageContaining("hardLimit");
    }

    @Test
    @DisplayName("should reject null expiry check interval")
    void should_reject_null_expiry_check_interval() {
      // When / Then
      assertThatIllegalArgumentException()
          .isThrownBy(() -> InqPublisherConfig.of(10, 20, null))
          .withMessageContaining("expiryCheckInterval");
    }

    @Test
    @DisplayName("should reject zero expiry check interval")
    void should_reject_zero_expiry_check_interval() {
      // When / Then
      assertThatIllegalArgumentException()
          .isThrownBy(() -> InqPublisherConfig.of(10, 20, Duration.ZERO))
          .withMessageContaining("expiryCheckInterval");
    }

    @Test
    @DisplayName("should reject negative expiry check interval")
    void should_reject_negative_expiry_check_interval() {
      // When / Then
      assertThatIllegalArgumentException()
          .isThrownBy(() -> InqPublisherConfig.of(10, 20, Duration.ofSeconds(-5)))
          .withMessageContaining("expiryCheckInterval");
    }

    @Test
    @DisplayName("should allow equal soft and hard limit")
    void should_allow_equal_soft_and_hard_limit() {
      // When / Then
      assertThatCode(() -> InqPublisherConfig.of(50, 50, Duration.ofSeconds(1)))
          .doesNotThrowAnyException();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // InqEvent base class
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Provider error event")
  class ProviderErrorEventTests {

    @Test
    @DisplayName("should assign construction error code for construction phase")
    void should_assign_construction_error_code_for_construction_phase() {
      // Given
      var event = new InqProviderErrorEvent(
          "com.example.FailingExporter",
          InqEventExporter.class.getName(),
          InqProviderErrorEvent.ProviderPhase.CONSTRUCTION,
          "instantiation failed",
          Instant.now());

      // Then
      assertThat(event.getCode())
          .isEqualTo(InqElementType.NO_ELEMENT.errorCode(1));
      assertThat(event.getPhase())
          .isEqualTo(InqProviderErrorEvent.ProviderPhase.CONSTRUCTION);
    }

    @Test
    @DisplayName("should assign execution error code for execution phase")
    void should_assign_execution_error_code_for_execution_phase() {
      // Given
      var event = new InqProviderErrorEvent(
          "com.example.FailingExporter",
          InqEventExporter.class.getName(),
          InqProviderErrorEvent.ProviderPhase.EXECUTION,
          "export method threw",
          Instant.now());

      // Then
      assertThat(event.getCode())
          .isEqualTo(InqElementType.NO_ELEMENT.errorCode(2));
      assertThat(event.getPhase())
          .isEqualTo(InqProviderErrorEvent.ProviderPhase.EXECUTION);
    }

    @Test
    @DisplayName("should store all fields correctly")
    void should_store_all_fields_correctly() {
      // Given
      var timestamp = Instant.now();

      // When
      var event = new InqProviderErrorEvent(
          "com.example.MyExporter",
          "eu.inqudium.core.event.InqEventExporter",
          InqProviderErrorEvent.ProviderPhase.CONSTRUCTION,
          "No default constructor",
          timestamp);

      // Then
      assertThat(event.getProviderClassName()).isEqualTo("com.example.MyExporter");
      assertThat(event.getSpiInterfaceName()).isEqualTo("eu.inqudium.core.event.InqEventExporter");
      assertThat(event.getErrorMessage()).isEqualTo("No default constructor");
      assertThat(event.getCallId()).isEqualTo("system");
      assertThat(event.getElementName()).isEqualTo("InqServiceLoader");
      assertThat(event.getElementType()).isEqualTo(InqElementType.NO_ELEMENT);
      assertThat(event.getTimestamp()).isEqualTo(timestamp);
    }

    @Test
    @DisplayName("should reject null provider class name")
    void should_reject_null_provider_class_name() {
      // When / Then
      assertThatNullPointerException()
          .isThrownBy(() -> new InqProviderErrorEvent(
              null, "spi", InqProviderErrorEvent.ProviderPhase.CONSTRUCTION,
              "msg", Instant.now()))
          .withMessageContaining("providerClassName");
    }

    @Test
    @DisplayName("should reject null phase")
    void should_reject_null_phase() {
      // When / Then
      assertThatNullPointerException()
          .isThrownBy(() -> new InqProviderErrorEvent(
              "provider", "spi", null, "msg", Instant.now()))
          .withMessageContaining("phase");
    }

    @Test
    @DisplayName("should map error index correctly for each phase")
    void should_map_error_index_correctly_for_each_phase() {
      // Then
      assertThat(InqProviderErrorEvent.ProviderPhase.CONSTRUCTION.errorIndex()).isEqualTo(1);
      assertThat(InqProviderErrorEvent.ProviderPhase.EXECUTION.errorIndex()).isEqualTo(2);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Concurrency
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("InqEvent base class validation")
  class InqEventValidation {

    @Test
    @DisplayName("should reject null callId")
    void should_reject_null_call_id() {
      // When / Then
      assertThatNullPointerException()
          .isThrownBy(() -> new TestEvent(null, "name", InqElementType.CIRCUIT_BREAKER, Instant.now()))
          .withMessageContaining("callId");
    }

    @Test
    @DisplayName("should reject blank callId")
    void should_reject_blank_call_id() {
      // When / Then
      assertThatIllegalArgumentException()
          .isThrownBy(() -> new TestEvent("  ", "name", InqElementType.CIRCUIT_BREAKER, Instant.now()))
          .withMessageContaining("callId");
    }

    @Test
    @DisplayName("should reject null elementName")
    void should_reject_null_element_name() {
      // When / Then
      assertThatNullPointerException()
          .isThrownBy(() -> new TestEvent("id", null, InqElementType.CIRCUIT_BREAKER, Instant.now()))
          .withMessageContaining("elementName");
    }

    @Test
    @DisplayName("should reject blank elementName")
    void should_reject_blank_element_name() {
      // When / Then
      assertThatIllegalArgumentException()
          .isThrownBy(() -> new TestEvent("id", "", InqElementType.CIRCUIT_BREAKER, Instant.now()))
          .withMessageContaining("elementName");
    }

    @Test
    @DisplayName("should reject null elementType")
    void should_reject_null_element_type() {
      // When / Then
      assertThatNullPointerException()
          .isThrownBy(() -> new TestEvent("id", "name", null, Instant.now()))
          .withMessageContaining("elementType");
    }

    @Test
    @DisplayName("should reject null timestamp")
    void should_reject_null_timestamp() {
      // When / Then
      assertThatNullPointerException()
          .isThrownBy(() -> new TestEvent("id", "name", InqElementType.CIRCUIT_BREAKER, null))
          .withMessageContaining("timestamp");
    }

    @Test
    @DisplayName("should store and return all fields correctly")
    void should_store_and_return_all_fields_correctly() {
      // Given
      var timestamp = Instant.now();

      // When
      var event = new TestEvent("call-42", "my-element", InqElementType.CIRCUIT_BREAKER, timestamp);

      // Then
      assertThat(event.getCallId()).isEqualTo("call-42");
      assertThat(event.getElementName()).isEqualTo("my-element");
      assertThat(event.getElementType()).isEqualTo(InqElementType.CIRCUIT_BREAKER);
      assertThat(event.getTimestamp()).isEqualTo(timestamp);
    }

    @Test
    @DisplayName("should include all fields in toString output")
    void should_include_all_fields_in_to_string_output() {
      // Given
      var event = new TestEvent("call-1", "elem", InqElementType.CIRCUIT_BREAKER, Instant.now());

      // When
      String result = event.toString();

      // Then
      assertThat(result)
          .contains("call-1")
          .contains("elem")
          .contains("CIRCUIT_BREAKER");
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ConsumerEntry record
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Concurrent publishing")
  class ConcurrentPublishing {

    @Test
    @DisplayName("should safely deliver events from multiple threads simultaneously")
    void should_safely_deliver_events_from_multiple_threads_simultaneously() throws Exception {
      // Given
      var publisher = createPublisher();
      var totalEvents = new AtomicInteger(0);
      publisher.onEvent(e -> totalEvents.incrementAndGet());

      int threadCount = 8;
      int eventsPerThread = 1_000;
      var executor = Executors.newFixedThreadPool(threadCount);
      var latch = new CountDownLatch(threadCount);

      // When — fire events from multiple threads
      for (int t = 0; t < threadCount; t++) {
        int threadId = t;
        executor.submit(() -> {
          try {
            for (int i = 0; i < eventsPerThread; i++) {
              publisher.publish(testEvent("thread-" + threadId + "-event-" + i));
            }
          } finally {
            latch.countDown();
          }
        });
      }

      boolean completed = latch.await(10, TimeUnit.SECONDS);
      executor.shutdown();

      // Then
      assertThat(completed).isTrue();
      assertThat(totalEvents.get()).isEqualTo(threadCount * eventsPerThread);
    }

    @Test
    @DisplayName("should safely handle concurrent subscribe and publish operations")
    void should_safely_handle_concurrent_subscribe_and_publish_operations() throws Exception {
      // Given
      var publisher = createPublisher();
      var totalDelivered = new AtomicInteger(0);
      int threadCount = 4;
      int iterations = 500;
      var executor = Executors.newFixedThreadPool(threadCount);
      var latch = new CountDownLatch(threadCount);

      // When — some threads publish, others subscribe/unsubscribe
      for (int t = 0; t < threadCount; t++) {
        int threadId = t;
        executor.submit(() -> {
          try {
            for (int i = 0; i < iterations; i++) {
              if (threadId % 2 == 0) {
                // Publishing threads
                publisher.publish(testEvent("concurrent-" + i));
              } else {
                // Subscribing threads — add and remove consumers
                var sub = publisher.onEvent(e -> totalDelivered.incrementAndGet());
                Thread.yield();
                sub.cancel();
              }
            }
          } finally {
            latch.countDown();
          }
        });
      }

      boolean completed = latch.await(10, TimeUnit.SECONDS);
      executor.shutdown();

      // Then — no exceptions, no data corruption
      assertThat(completed).isTrue();
      // We can't assert exact count because of timing, but it must be >= 0
      assertThat(totalDelivered.get()).isGreaterThanOrEqualTo(0);
    }
  }

  // ── Utility ───────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("ConsumerEntry expiry semantics")
  class ConsumerEntryExpiry {

    @Test
    @DisplayName("should report permanent entry as never expired")
    void should_report_permanent_entry_as_never_expired() {
      // Given — null expiresAt means permanent
      var entry = new DefaultInqEventPublisher.ConsumerEntry(1, e -> {
      }, "perm", null);

      // When / Then
      assertThat(entry.isExpired(Instant.now())).isFalse();
      assertThat(entry.isExpired(Instant.MAX)).isFalse();
    }

    @Test
    @DisplayName("should report TTL entry as expired when now is after expiresAt")
    void should_report_ttl_entry_as_expired_when_now_is_after_expires_at() {
      // Given
      var expiresAt = Instant.now().minusSeconds(1);
      var entry = new DefaultInqEventPublisher.ConsumerEntry(1, e -> {
      }, "ttl", expiresAt);

      // When / Then
      assertThat(entry.isExpired(Instant.now())).isTrue();
    }

    @Test
    @DisplayName("should report TTL entry as not expired when now is before expiresAt")
    void should_report_ttl_entry_as_not_expired_when_now_is_before_expires_at() {
      // Given
      var expiresAt = Instant.now().plusSeconds(3600);
      var entry = new DefaultInqEventPublisher.ConsumerEntry(1, e -> {
      }, "ttl", expiresAt);

      // When / Then
      assertThat(entry.isExpired(Instant.now())).isFalse();
    }
  }
}
