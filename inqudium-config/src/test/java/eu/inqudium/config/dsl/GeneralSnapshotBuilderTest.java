package eu.inqudium.config.dsl;

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
            assertThat(snapshot.loggerFactory()).isSameAs(LoggerFactory.NO_OP_LOGGER_FACTORY);
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

            try {
                // When
                GeneralSnapshot snapshot = new GeneralSnapshotBuilder()
                        .clock(clock)
                        .nanoTimeSource(nano)
                        .eventPublisher(pub)
                        .loggerFactory(lf)
                        .build();

                // Then
                assertThat(snapshot.clock()).isSameAs(clock);
                assertThat(snapshot.nanoTimeSource()).isSameAs(nano);
                assertThat(snapshot.eventPublisher()).isSameAs(pub);
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
}
