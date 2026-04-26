package eu.inqudium.config.snapshot;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventExporterRegistry;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.event.InqPublisherConfig;
import eu.inqudium.core.log.LoggerFactory;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("GeneralSnapshot")
class GeneralSnapshotTest {

    private InqEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = InqEventPublisher.create(
                "test",
                InqElementType.NO_ELEMENT,
                new InqEventExporterRegistry(),
                InqPublisherConfig.defaultConfig());
    }

    @AfterEach
    void tearDown() throws Exception {
        publisher.close();
    }

    @Test
    void should_carry_every_field_through_the_compact_constructor() {
        // Given
        InqClock clock = InqClock.system();
        InqNanoTimeSource nanoTimeSource = InqNanoTimeSource.system();
        LoggerFactory loggerFactory = LoggerFactory.NO_OP_LOGGER_FACTORY;

        // When
        GeneralSnapshot snapshot = new GeneralSnapshot(clock, nanoTimeSource, publisher, loggerFactory);

        // Then
        assertThat(snapshot.clock()).isSameAs(clock);
        assertThat(snapshot.nanoTimeSource()).isSameAs(nanoTimeSource);
        assertThat(snapshot.eventPublisher()).isSameAs(publisher);
        assertThat(snapshot.loggerFactory()).isSameAs(loggerFactory);
    }

    @Test
    void should_reject_a_null_clock() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new GeneralSnapshot(
                        null, InqNanoTimeSource.system(), publisher, LoggerFactory.NO_OP_LOGGER_FACTORY))
                .withMessageContaining("clock");
    }

    @Test
    void should_reject_a_null_nano_time_source() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new GeneralSnapshot(
                        InqClock.system(), null, publisher, LoggerFactory.NO_OP_LOGGER_FACTORY))
                .withMessageContaining("nanoTimeSource");
    }

    @Test
    void should_reject_a_null_event_publisher() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new GeneralSnapshot(
                        InqClock.system(), InqNanoTimeSource.system(), null,
                        LoggerFactory.NO_OP_LOGGER_FACTORY))
                .withMessageContaining("eventPublisher");
    }

    @Test
    void should_reject_a_null_logger_factory() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new GeneralSnapshot(
                        InqClock.system(), InqNanoTimeSource.system(), publisher, null))
                .withMessageContaining("loggerFactory");
    }
}
