package eu.inqudium.config.snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BulkheadSnapshot")
class BulkheadSnapshotTest {

    /**
     * Tiny test-only builder. Each test mutates only the field it cares about and leaves the rest
     * at validation-passing defaults.
     */
    private static final class TestBuilder {
        String name = "inventory";
        int maxConcurrentCalls = 10;
        Duration maxWaitDuration = Duration.ofMillis(100);
        Set<String> tags = Set.of();
        String derivedFromPreset = null;

        TestBuilder with(Consumer<TestBuilder> mutator) {
            mutator.accept(this);
            return this;
        }

        BulkheadSnapshot build() {
            return new BulkheadSnapshot(
                    name, maxConcurrentCalls, maxWaitDuration, tags, derivedFromPreset);
        }
    }

    private static TestBuilder builder() {
        return new TestBuilder();
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        void should_carry_every_field_through_the_compact_constructor() {
            // Given / When
            BulkheadSnapshot snapshot = new BulkheadSnapshot(
                    "inventory",
                    25,
                    Duration.ofMillis(500),
                    Set.of("payment", "critical"),
                    "balanced");

            // Then
            assertThat(snapshot.name()).isEqualTo("inventory");
            assertThat(snapshot.maxConcurrentCalls()).isEqualTo(25);
            assertThat(snapshot.maxWaitDuration()).isEqualTo(Duration.ofMillis(500));
            assertThat(snapshot.tags()).containsExactlyInAnyOrder("payment", "critical");
            assertThat(snapshot.derivedFromPreset()).isEqualTo("balanced");
        }

        @Test
        void should_allow_a_zero_max_wait_duration_for_fail_fast_semantics() {
            // The protective preset uses maxWaitDuration = 0 to express fail-fast.
            // Given / When
            BulkheadSnapshot snapshot = builder()
                    .with(b -> b.maxWaitDuration = Duration.ZERO)
                    .build();

            // Then
            assertThat(snapshot.maxWaitDuration()).isZero();
        }

        @Test
        void should_allow_a_null_derived_from_preset() {
            // Snapshots assembled without a preset baseline carry derivedFromPreset == null.
            // Given / When
            BulkheadSnapshot snapshot = builder()
                    .with(b -> b.derivedFromPreset = null)
                    .build();

            // Then
            assertThat(snapshot.derivedFromPreset()).isNull();
        }
    }

    @Nested
    @DisplayName("name validation")
    class NameValidation {

        @Test
        void should_reject_a_null_name() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> builder().with(b -> b.name = null).build())
                    .withMessageContaining("name");
        }

        @Test
        void should_reject_a_blank_name() {
            // Given / When / Then
            assertThatThrownBy(() -> builder().with(b -> b.name = "   ").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name must not be blank");
        }

        @Test
        void should_reject_an_empty_name() {
            // Given / When / Then
            assertThatThrownBy(() -> builder().with(b -> b.name = "").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name must not be blank");
        }
    }

    @Nested
    @DisplayName("maxConcurrentCalls validation")
    class MaxConcurrentCallsValidation {

        @Test
        void should_reject_zero() {
            // Given / When / Then
            assertThatThrownBy(() -> builder().with(b -> b.maxConcurrentCalls = 0).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxConcurrentCalls must be positive");
        }

        @Test
        void should_reject_a_negative_value() {
            // Given / When / Then
            assertThatThrownBy(() -> builder().with(b -> b.maxConcurrentCalls = -1).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("got: -1");
        }
    }

    @Nested
    @DisplayName("maxWaitDuration validation")
    class MaxWaitDurationValidation {

        @Test
        void should_reject_a_null_duration() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> builder().with(b -> b.maxWaitDuration = null).build())
                    .withMessageContaining("maxWaitDuration");
        }

        @Test
        void should_reject_a_negative_duration() {
            // Given / When / Then
            assertThatThrownBy(() -> builder()
                    .with(b -> b.maxWaitDuration = Duration.ofMillis(-1))
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxWaitDuration must not be negative");
        }
    }

    @Nested
    @DisplayName("tags validation and copy")
    class TagsValidation {

        @Test
        void should_default_a_null_tag_set_to_empty() {
            // Given / When
            BulkheadSnapshot snapshot = builder().with(b -> b.tags = null).build();

            // Then
            assertThat(snapshot.tags()).isEmpty();
        }

        @Test
        void should_defensively_copy_the_caller_supplied_tag_set() {
            // What is to be tested: that the snapshot does not alias a mutable caller set, so
            // post-construction mutation of the caller's set cannot leak into the snapshot.
            // Why successful: snapshots are immutable value objects; aliasing would corrupt them
            // and break the thread-safety guarantees they advertise.
            // Why important: snapshots travel across threads through the LiveContainer; an
            // aliased mutable set is a recipe for data races.

            // Given
            Set<String> mutable = new HashSet<>();
            mutable.add("payment");
            BulkheadSnapshot snapshot = builder().with(b -> b.tags = mutable).build();

            // When
            mutable.add("critical");

            // Then
            assertThat(snapshot.tags()).containsExactly("payment");
        }
    }

    @Nested
    @DisplayName("derivedFromPreset validation")
    class DerivedFromPresetValidation {

        @Test
        void should_reject_a_blank_preset_label() {
            // Given / When / Then
            assertThatThrownBy(() -> builder().with(b -> b.derivedFromPreset = "   ").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("derivedFromPreset must not be blank when set");
        }

        @Test
        void should_reject_an_empty_preset_label() {
            // Given / When / Then
            assertThatThrownBy(() -> builder().with(b -> b.derivedFromPreset = "").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("derivedFromPreset must not be blank when set");
        }
    }

    @Nested
    @DisplayName("ComponentSnapshot contract")
    class ComponentSnapshotContract {

        @Test
        void should_expose_the_name_through_the_component_snapshot_interface() {
            // Given
            BulkheadSnapshot snapshot = builder().with(b -> b.name = "payments").build();

            // When
            ComponentSnapshot view = snapshot;

            // Then
            assertThat(view.name()).isEqualTo("payments");
        }
    }
}
