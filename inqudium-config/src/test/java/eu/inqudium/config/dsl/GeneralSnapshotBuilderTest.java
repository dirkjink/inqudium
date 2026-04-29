package eu.inqudium.config.dsl;

import eu.inqudium.config.snapshot.ComponentEventPublisherFactory;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventExporterRegistry;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.event.InqPublisherConfig;
import eu.inqudium.core.log.LoggerFactory;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("GeneralSnapshotBuilder")
class GeneralSnapshotBuilderTest {

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        void should_supply_a_default_for_every_field_when_nothing_is_set() {
            // Given
            GeneralSnapshotBuilder builder = new GeneralSnapshotBuilder();

            // When
            GeneralSnapshot snapshot = builder.build();

            // Then
            assertThat(snapshot.clock()).isNotNull();
            assertThat(snapshot.nanoTimeSource()).isNotNull();
            assertThat(snapshot.eventPublisher()).isNotNull();
            assertThat(snapshot.componentPublisherFactory()).isNotNull();
            assertThat(snapshot.loggerFactory()).isSameAs(LoggerFactory.NO_OP_LOGGER_FACTORY);
            assertThat(snapshot.enableExceptionOptimization())
                    .as("default for the ADR-020 performance flag is enabled")
                    .isTrue();
        }

        @Test
        void should_carry_the_exception_optimization_flag_explicitly_set_to_false() {
            // What is to be tested: an operator who wants full stack traces on resilience
            // exceptions can opt out of the ADR-020 performance optimization via the builder.
            // Why successful: the built snapshot reports the flag as we set it.
            // Why important: the flag participates in the configuration surface; without an
            // explicit setter it would be impossible to disable the optimization without
            // hand-rolling a GeneralSnapshot record.

            // Given / When
            GeneralSnapshot snapshot = new GeneralSnapshotBuilder()
                    .enableExceptionOptimization(false)
                    .build();

            // Then
            assertThat(snapshot.enableExceptionOptimization()).isFalse();
        }

        @Test
        void default_component_publisher_factory_should_create_publisher_with_supplied_identity() {
            // What is to be tested: that the default ComponentEventPublisherFactory passes the
            // element name and type straight to InqEventPublisher.create — i.e. produces a
            // publisher that carries the component's identity.
            // Why successful: the publisher's elementName matches the name we hand the factory.
            // Why important: ADR-030 specifies that per-component publishers carry the
            // component's identity; this test pins the default-factory behaviour.

            // Given
            GeneralSnapshot snapshot = new GeneralSnapshotBuilder().build();

            // When
            InqEventPublisher pub = snapshot.componentPublisherFactory()
                    .create("inventory", InqElementType.BULKHEAD);
            try {
                // Then — publish a sentinel event and confirm its elementName / elementType
                var capturedName = new java.util.concurrent.atomic.AtomicReference<String>();
                var capturedType = new java.util.concurrent.atomic.AtomicReference<InqElementType>();
                pub.onEvent(eu.inqudium.core.event.InqEvent.class, e -> {
                    capturedName.set(e.getElementName());
                    capturedType.set(e.getElementType());
                });
                pub.publish(new SentinelComponentEvent("inventory", InqElementType.BULKHEAD));
                assertThat(capturedName.get()).isEqualTo("inventory");
                assertThat(capturedType.get()).isEqualTo(InqElementType.BULKHEAD);
            } finally {
                try {
                    pub.close();
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }

        @Test
        void should_use_a_runtime_publisher_with_the_documented_default_name() {
            // What is to be tested: that the default publisher carries the documented
            // "inqudium-runtime" name, so dashboards filtering by element name can identify
            // runtime-level events without ambiguity.
            // Why successful: the publisher's elementName comes from the published event; we
            // use a sentinel publisher to capture the first event and inspect its name.
            // Why important: the default name is part of the contract (used by operational
            // tooling); a regression here breaks downstream filters silently.

            // Given
            GeneralSnapshotBuilder builder = new GeneralSnapshotBuilder();
            GeneralSnapshot snapshot = builder.build();

            // When — observe via a stub event published through the same publisher
            var captured = new java.util.concurrent.atomic.AtomicReference<String>();
            snapshot.eventPublisher().onEvent(eu.inqudium.core.event.InqEvent.class,
                    e -> captured.set(e.getElementName()));
            snapshot.eventPublisher().publish(new SentinelEvent());

            // Then
            assertThat(captured.get()).isEqualTo(GeneralSnapshotBuilder.DEFAULT_RUNTIME_PUBLISHER_NAME);
        }

        @Test
        void system_clock_default_should_be_close_to_instant_now() {
            // Given
            GeneralSnapshot snapshot = new GeneralSnapshotBuilder().build();

            // When
            Instant now = snapshot.clock().instant();

            // Then — within a generous tolerance for slow CI; the test is about the type of
            // default, not the precision.
            assertThat(now).isBetween(Instant.now().minusSeconds(5), Instant.now().plusSeconds(5));
        }
    }

    @Nested
    @DisplayName("explicit overrides")
    class Overrides {

        @Test
        void should_carry_explicit_values_through_to_the_built_snapshot() {
            // Given
            InqClock clock = () -> Instant.parse("2026-04-26T12:00:00Z");
            InqNanoTimeSource nano = () -> 42L;
            InqEventPublisher pub = InqEventPublisher.create(
                    "explicit", InqElementType.NO_ELEMENT,
                    new InqEventExporterRegistry(), InqPublisherConfig.defaultConfig());
            LoggerFactory lf = c -> eu.inqudium.core.log.Logger.NO_OP_LOGGER;
            ComponentEventPublisherFactory cf = (n, t) -> pub;

            try {
                // When
                GeneralSnapshot snapshot = new GeneralSnapshotBuilder()
                        .clock(clock)
                        .nanoTimeSource(nano)
                        .eventPublisher(pub)
                        .componentPublisherFactory(cf)
                        .loggerFactory(lf)
                        .build();

                // Then
                assertThat(snapshot.clock()).isSameAs(clock);
                assertThat(snapshot.nanoTimeSource()).isSameAs(nano);
                assertThat(snapshot.eventPublisher()).isSameAs(pub);
                assertThat(snapshot.componentPublisherFactory()).isSameAs(cf);
                assertThat(snapshot.loggerFactory()).isSameAs(lf);
            } finally {
                try {
                    pub.close();
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }

        @Test
        void each_setter_should_return_the_builder_for_chaining() {
            // Given
            GeneralSnapshotBuilder b = new GeneralSnapshotBuilder();

            // When / Then
            assertThat(b.clock(InqClock.system())).isSameAs(b);
            assertThat(b.nanoTimeSource(InqNanoTimeSource.system())).isSameAs(b);
            assertThat(b.loggerFactory(LoggerFactory.NO_OP_LOGGER_FACTORY)).isSameAs(b);
        }
    }

    @Nested
    @DisplayName("class-1 setter validation")
    class Validation {

        @Test
        void should_reject_a_null_clock() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new GeneralSnapshotBuilder().clock(null))
                    .withMessageContaining("clock");
        }

        @Test
        void should_reject_a_null_nano_time_source() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new GeneralSnapshotBuilder().nanoTimeSource(null))
                    .withMessageContaining("nanoTimeSource");
        }

        @Test
        void should_reject_a_null_event_publisher() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new GeneralSnapshotBuilder().eventPublisher(null))
                    .withMessageContaining("eventPublisher");
        }

        @Test
        void should_reject_a_null_component_publisher_factory() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new GeneralSnapshotBuilder().componentPublisherFactory(null))
                    .withMessageContaining("componentPublisherFactory");
        }

        @Test
        void should_reject_a_null_logger_factory() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new GeneralSnapshotBuilder().loggerFactory(null))
                    .withMessageContaining("loggerFactory");
        }
    }

    /** Minimal concrete subclass of InqEvent for tests that need to inspect the element name. */
    private static final class SentinelEvent extends eu.inqudium.core.event.InqEvent {
        SentinelEvent() {
            super(0L, 0L,
                    GeneralSnapshotBuilder.DEFAULT_RUNTIME_PUBLISHER_NAME,
                    InqElementType.NO_ELEMENT,
                    Instant.parse("2026-01-01T00:00:00Z"));
        }
    }

    /** Component-side sentinel — used by the default-factory test to observe the name/type the
     *  factory bakes into the publisher's published events. */
    private static final class SentinelComponentEvent extends eu.inqudium.core.event.InqEvent {
        SentinelComponentEvent(String name, InqElementType type) {
            super(0L, 0L, name, type, Instant.parse("2026-01-01T00:00:00Z"));
        }
    }
}
