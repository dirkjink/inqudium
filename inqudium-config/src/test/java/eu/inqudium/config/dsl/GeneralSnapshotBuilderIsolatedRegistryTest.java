package eu.inqudium.config.dsl;

import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEvent;
import eu.inqudium.core.event.InqEventExporter;
import eu.inqudium.core.event.InqEventExporterRegistry;
import eu.inqudium.core.event.InqEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@link GeneralSnapshotBuilder#isolatedEventRegistry()} (ADR-031). Per-runtime
 * registry isolation is opt-in: the default builder still binds to the global default registry
 * (matching pre-2.8 behaviour), and {@code isolatedEventRegistry()} swaps in a fresh private
 * registry for both the runtime publisher and every component publisher the factory mints.
 *
 * <p>Tests that touch the global default registry save and restore it in {@link AfterEach} so
 * one test does not leak state into the next.
 */
@DisplayName("GeneralSnapshotBuilder.isolatedEventRegistry")
class GeneralSnapshotBuilderIsolatedRegistryTest {

    private InqEventExporterRegistry originalDefault;

    @BeforeEach
    void rememberDefaultRegistry() {
        // Capture the (possibly lazily-allocated) default so each test can install a fresh
        // sentinel and restore the original in tearDown without leaking state into siblings.
        originalDefault = InqEventExporterRegistry.getDefault();
    }

    @AfterEach
    void restoreDefaultRegistry() {
        InqEventExporterRegistry.setDefault(originalDefault);
    }

    @Nested
    @DisplayName("cross-runtime isolation")
    class CrossRuntime {

        @Test
        void should_keep_events_published_on_runtime_A_invisible_to_runtime_B() {
            // What is to be tested: two runtimes built with isolatedEventRegistry() each have
            // their own runtime-scoped publisher; an event published on runtime A's publisher
            // never reaches a subscriber on runtime B's publisher.
            // Why important: this is the headline contract of ADR-031 — embedding two Inqudium
            // runtimes in the same JVM (e.g. multi-tenant test harness) requires that their
            // event streams not cross.

            // Given
            GeneralSnapshot a = new GeneralSnapshotBuilder().isolatedEventRegistry().build();
            GeneralSnapshot b = new GeneralSnapshotBuilder().isolatedEventRegistry().build();

            try {
                List<InqEvent> seenByA = new ArrayList<>();
                List<InqEvent> seenByB = new ArrayList<>();
                a.eventPublisher().onEvent(InqEvent.class, seenByA::add);
                b.eventPublisher().onEvent(InqEvent.class, seenByB::add);

                // When — publish only on A's runtime publisher
                a.eventPublisher().publish(new SentinelEvent("from-a"));

                // Then
                assertThat(seenByA).hasSize(1);
                assertThat(seenByA.get(0).getElementName()).isEqualTo("from-a");
                assertThat(seenByB)
                        .as("runtime B's subscriber must not see runtime A's event")
                        .isEmpty();
            } finally {
                closeQuietly(a.eventPublisher());
                closeQuietly(b.eventPublisher());
            }
        }
    }

    @Nested
    @DisplayName("intra-runtime registry binding")
    class IntraRuntime {

        @Test
        void should_route_runtime_and_component_events_off_the_global_default_registry() {
            // What is to be tested: events published through the runtime publisher AND through
            // a component publisher minted by the isolated factory are both routed off the
            // global default registry. Verified by registering a sentinel exporter on the
            // global default and confirming it sees neither event.
            // Why successful: the captured-event list on the default-registered exporter stays
            // empty after both publishes.
            // Why important: per ADR-031, isolation must apply to every publisher the runtime
            // hands out, not just the runtime-scoped one — an embedded runtime that leaked
            // component events to the global default would defeat the multi-tenant guarantee.

            // Given — install a sentinel on the global default
            CapturingExporter defaultSentinel = new CapturingExporter();
            InqEventExporterRegistry isolatedDefault = new InqEventExporterRegistry();
            isolatedDefault.register(defaultSentinel);
            InqEventExporterRegistry.setDefault(isolatedDefault);

            // When — build with isolatedEventRegistry and publish on both publisher kinds
            GeneralSnapshot snapshot =
                    new GeneralSnapshotBuilder().isolatedEventRegistry().build();
            InqEventPublisher componentPub = snapshot.componentPublisherFactory()
                    .create("inventory", InqElementType.BULKHEAD);
            try {
                List<InqEvent> seenByRuntimeSub = new ArrayList<>();
                List<InqEvent> seenByComponentSub = new ArrayList<>();
                snapshot.eventPublisher().onEvent(InqEvent.class, seenByRuntimeSub::add);
                componentPub.onEvent(InqEvent.class, seenByComponentSub::add);

                snapshot.eventPublisher().publish(new SentinelEvent("runtime-event"));
                componentPub.publish(new SentinelEvent("component-event"));

                // Then — both subscribers see their own publishers' events
                assertThat(seenByRuntimeSub).hasSize(1);
                assertThat(seenByRuntimeSub.get(0).getElementName()).isEqualTo("runtime-event");
                assertThat(seenByComponentSub).hasSize(1);
                assertThat(seenByComponentSub.get(0).getElementName()).isEqualTo("component-event");

                // ... and the global-default exporter saw neither
                assertThat(defaultSentinel.captured)
                        .as("isolated registry must not forward to the global default")
                        .isEmpty();
            } finally {
                closeQuietly(componentPub);
                closeQuietly(snapshot.eventPublisher());
            }
        }
    }

    @Nested
    @DisplayName("default behaviour without isolatedEventRegistry()")
    class DefaultBehaviour {

        @Test
        void should_route_runtime_events_through_the_global_default_registry() {
            // What is to be tested: when isolatedEventRegistry() is not called, the runtime
            // publisher still binds to InqEventExporterRegistry.getDefault() — pre-2.8 behaviour
            // is unchanged.
            // Why important: a regression that flipped the default to "isolated" would break
            // every consumer that relies on the global default being the live target — the
            // installed Micrometer / OpenTelemetry exporters in production code paths.

            // Given — sentinel registered on the global default
            CapturingExporter sentinel = new CapturingExporter();
            InqEventExporterRegistry sentinelRegistry = new InqEventExporterRegistry();
            sentinelRegistry.register(sentinel);
            InqEventExporterRegistry.setDefault(sentinelRegistry);

            GeneralSnapshot snapshot = new GeneralSnapshotBuilder().build();
            try {
                // When
                snapshot.eventPublisher().publish(new SentinelEvent("default-event"));

                // Then
                assertThat(sentinel.captured)
                        .as("the default builder still publishes through getDefault()")
                        .hasSize(1);
                assertThat(sentinel.captured.get(0).getElementName())
                        .isEqualTo("default-event");
            } finally {
                closeQuietly(snapshot.eventPublisher());
            }
        }
    }

    @Nested
    @DisplayName("publisher behaviour after close")
    class AfterClose {

        @Test
        void publish_after_close_should_continue_to_dispatch_to_subscribers() {
            // What is to be tested: closing the isolated runtime publisher does NOT short-
            // circuit subsequent publishes. The DefaultInqEventPublisher.close contract only
            // shuts the consumer-expiry watchdog down; the publish path itself stays
            // functional. Pinning this so a future regression that changes close semantics
            // surfaces explicitly.
            // Why important: tooling code may close publishers as part of orderly shutdown
            // sequences while events still trickle in; today's behaviour is "no-op close, keep
            // serving subscribers". A change that flipped this to "throw on publish after
            // close" would break orderly-shutdown paths silently.

            GeneralSnapshot snapshot =
                    new GeneralSnapshotBuilder().isolatedEventRegistry().build();
            AtomicReference<InqEvent> captured = new AtomicReference<>();
            snapshot.eventPublisher().onEvent(InqEvent.class, captured::set);

            // When — close, then publish
            closeQuietly(snapshot.eventPublisher());
            snapshot.eventPublisher().publish(new SentinelEvent("after-close"));

            // Then
            assertThat(captured.get())
                    .as("subscriber still fires after publisher.close()")
                    .isNotNull();
            assertThat(captured.get().getElementName()).isEqualTo("after-close");
        }
    }

    @Nested
    @DisplayName("repeat invocation")
    class RepeatInvocation {

        @Test
        void should_replace_the_first_registry_with_a_fresh_one_on_second_call() {
            // What is to be tested: calling isolatedEventRegistry() twice produces two distinct
            // registries; the second call's runtime publisher is not the first call's. This
            // pins the documented "last-writer-wins" semantics on the builder.

            GeneralSnapshotBuilder builder = new GeneralSnapshotBuilder();
            GeneralSnapshot first = builder.isolatedEventRegistry().build();

            // Same builder reused — second isolatedEventRegistry call replaces the wiring.
            GeneralSnapshot second = builder.isolatedEventRegistry().build();

            try {
                assertThat(second.eventPublisher())
                        .as("second isolatedEventRegistry call replaces the first publisher")
                        .isNotSameAs(first.eventPublisher());
                assertThat(second.componentPublisherFactory())
                        .as("second call also replaces the component-publisher factory")
                        .isNotSameAs(first.componentPublisherFactory());
            } finally {
                closeQuietly(first.eventPublisher());
                closeQuietly(second.eventPublisher());
            }
        }
    }

    private static void closeQuietly(InqEventPublisher publisher) {
        try {
            publisher.close();
        } catch (Exception ignored) {
            // best effort
        }
    }

    /** Test exporter that captures every event it sees. */
    private static final class CapturingExporter implements InqEventExporter {
        final List<InqEvent> captured = new ArrayList<>();

        @Override
        public void export(InqEvent event) {
            captured.add(event);
        }
    }

    /** Concrete InqEvent for the tests; no fields beyond the base class. */
    private static final class SentinelEvent extends InqEvent {
        SentinelEvent(String elementName) {
            super(0L, 0L, elementName, InqElementType.NO_ELEMENT,
                    Instant.parse("2026-04-28T00:00:00Z"));
        }
    }
}
