package eu.inqudium.imperative.runtime;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.ImperativeBulkhead;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Inqudium.configure() end-to-end")
class InqudiumConfigureTest {

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        void should_build_a_runtime_with_one_bulkhead_and_query_it() {
            // What is to be tested: the entire build pipeline — DSL → patch accumulation →
            // ServiceLoader-discovered ImperativeProvider → DefaultImperative materialization.
            // Why successful: the bulkhead is reachable through runtime.imperative() with the
            // configuration the user wrote in the lambda.
            // Why important: this is the central phase-1 acceptance criterion (build a runtime
            // with one bulkhead, verify it can be queried).

            // Given / When
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im
                            .bulkhead("inventory", b -> b.balanced().maxConcurrentCalls(15)))
                    .build()) {

                // Then
                ImperativeBulkhead bh = runtime.imperative().bulkhead("inventory");
                BulkheadSnapshot snapshot = bh.snapshot();
                assertThat(snapshot.name()).isEqualTo("inventory");
                assertThat(snapshot.maxConcurrentCalls()).isEqualTo(15);
                assertThat(snapshot.maxWaitDuration()).isEqualTo(Duration.ofMillis(500));
                assertThat(snapshot.derivedFromPreset()).isEqualTo("balanced");
            }
        }

        @Test
        void should_build_an_empty_imperative_container_when_no_section_was_declared() {
            // Per ADR-026: if the imperative module is on the classpath but no .imperative(...)
            // section was declared, the container exists but is empty.
            // Given / When
            try (InqRuntime runtime = Inqudium.configure().build()) {
                // Then
                assertThat(runtime.imperative().bulkheadNames()).isEmpty();
            }
        }

        @Test
        void should_register_multiple_bulkheads_in_one_section() {
            // Given / When
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im
                            .bulkhead("a", b -> b.balanced())
                            .bulkhead("b", b -> b.protective())
                            .bulkhead("c", b -> b.permissive()))
                    .build()) {

                // Then
                assertThat(runtime.imperative().bulkheadNames())
                        .containsExactlyInAnyOrder("a", "b", "c");
            }
        }
    }

    @Nested
    @DisplayName("imperative container queries")
    class ContainerQueries {

        @Test
        void should_throw_when_bulkhead_is_unknown_to_strict_lookup() {
            // Given
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("a", b -> b.balanced()))
                    .build()) {
                // When / Then
                assertThatThrownBy(() -> runtime.imperative().bulkhead("missing"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("missing")
                        .hasMessageContaining("Available: [a]");
            }
        }

        @Test
        void findBulkhead_should_return_empty_when_unknown() {
            // Given
            try (InqRuntime runtime = Inqudium.configure().build()) {
                // When / Then
                assertThat(runtime.imperative().findBulkhead("missing"))
                        .isEqualTo(Optional.empty());
            }
        }

        @Test
        void findBulkhead_should_return_the_handle_when_known() {
            // Given
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("a", b -> b.balanced()))
                    .build()) {
                // When / Then
                Optional<ImperativeBulkhead> handle = runtime.imperative().findBulkhead("a");
                assertThat(handle).isPresent();
                assertThat(handle.get().name()).isEqualTo("a");
            }
        }
    }

    @Nested
    @DisplayName("config view")
    class ConfigView {

        @Test
        void config_bulkheads_should_aggregate_imperative_bulkhead_snapshots() {
            // Given / When
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im
                            .bulkhead("a", b -> b.balanced())
                            .bulkhead("b", b -> b.protective()))
                    .build()) {

                // Then
                assertThat(runtime.config().bulkheads()
                        .map(BulkheadSnapshot::name)
                        .toList())
                        .containsExactlyInAnyOrder("a", "b");
            }
        }

        @Test
        void config_findBulkhead_should_disambiguate_by_paradigm() {
            // Given / When
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("a", b -> b.balanced()))
                    .build()) {

                // Then
                Optional<BulkheadSnapshot> found =
                        runtime.config().findBulkhead("a", ImperativeTag.INSTANCE);
                assertThat(found).isPresent();
                assertThat(found.get().name()).isEqualTo("a");
            }
        }

        @Test
        void config_general_should_mirror_runtime_general() {
            // Given
            try (InqRuntime runtime = Inqudium.configure().build()) {
                // When / Then
                assertThat(runtime.config().general()).isSameAs(runtime.general());
            }
        }
    }

    @Nested
    @DisplayName("close lifecycle")
    class Close {

        @Test
        void should_render_runtime_inert_after_close() {
            // Given
            InqRuntime runtime = Inqudium.configure().build();

            // When
            runtime.close();

            // Then
            assertThatThrownBy(runtime::imperative)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
            assertThatThrownBy(runtime::general)
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(runtime::config)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void close_should_be_idempotent() {
            // Given
            InqRuntime runtime = Inqudium.configure().build();

            // When
            runtime.close();
            runtime.close();
            runtime.close();

            // Then — no exception; AutoCloseable contract honoured
            assertThatThrownBy(runtime::imperative)
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
