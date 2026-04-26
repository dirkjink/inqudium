package eu.inqudium.config.patch;

import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadField;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("BulkheadPatch")
class BulkheadPatchTest {

    private static BulkheadSnapshot baseSnapshot() {
        return new BulkheadSnapshot(
                "inventory",
                10,
                Duration.ofMillis(100),
                Set.of("payment"),
                "balanced",
                BulkheadEventConfig.disabled());
    }

    @Nested
    @DisplayName("empty patch")
    class EmptyPatch {

        @Test
        void should_have_no_touched_fields() {
            // Given / When
            BulkheadPatch patch = new BulkheadPatch();

            // Then
            assertThat(patch.touchedFields()).isEmpty();
            for (BulkheadField field : BulkheadField.values()) {
                assertThat(patch.isTouched(field)).isFalse();
            }
        }

        @Test
        void should_produce_an_unchanged_snapshot_on_apply() {
            // What is to be tested: that an empty patch is a no-op when applied. Why: this is the
            // foundation of inheritance — every untouched field must come from the base. If empty
            // patches changed anything, every other patch would too.
            // Why important: the dispatcher applies patches even when the user only intended to
            // touch one field; the rest must be inherited unchanged.

            // Given
            BulkheadSnapshot base = baseSnapshot();
            BulkheadPatch patch = new BulkheadPatch();

            // When
            BulkheadSnapshot result = patch.applyTo(base);

            // Then
            assertThat(result).isEqualTo(base);
        }

        @Test
        void should_reject_a_null_base_on_apply() {
            // Given
            BulkheadPatch patch = new BulkheadPatch();

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> patch.applyTo(null))
                    .withMessageContaining("base");
        }
    }

    @Nested
    @DisplayName("single-field touches")
    class SingleFieldTouches {

        @Test
        void should_change_only_the_name_when_only_name_is_touched() {
            // Given
            BulkheadSnapshot base = baseSnapshot();
            BulkheadPatch patch = new BulkheadPatch();

            // When
            patch.touchName("renamed");
            BulkheadSnapshot result = patch.applyTo(base);

            // Then
            assertThat(result.name()).isEqualTo("renamed");
            assertThat(result.maxConcurrentCalls()).isEqualTo(base.maxConcurrentCalls());
            assertThat(result.maxWaitDuration()).isEqualTo(base.maxWaitDuration());
            assertThat(result.tags()).isEqualTo(base.tags());
            assertThat(result.derivedFromPreset()).isEqualTo(base.derivedFromPreset());
            assertThat(patch.touchedFields()).containsExactly(BulkheadField.NAME);
        }

        @Test
        void should_change_only_max_concurrent_calls_when_only_that_field_is_touched() {
            // Given
            BulkheadSnapshot base = baseSnapshot();
            BulkheadPatch patch = new BulkheadPatch();

            // When
            patch.touchMaxConcurrentCalls(25);
            BulkheadSnapshot result = patch.applyTo(base);

            // Then
            assertThat(result.maxConcurrentCalls()).isEqualTo(25);
            assertThat(result.name()).isEqualTo(base.name());
            assertThat(result.maxWaitDuration()).isEqualTo(base.maxWaitDuration());
            assertThat(result.tags()).isEqualTo(base.tags());
            assertThat(result.derivedFromPreset()).isEqualTo(base.derivedFromPreset());
            assertThat(patch.touchedFields()).containsExactly(BulkheadField.MAX_CONCURRENT_CALLS);
        }

        @Test
        void should_change_only_max_wait_duration_when_only_that_field_is_touched() {
            // Given
            BulkheadSnapshot base = baseSnapshot();
            BulkheadPatch patch = new BulkheadPatch();

            // When
            patch.touchMaxWaitDuration(Duration.ofSeconds(2));
            BulkheadSnapshot result = patch.applyTo(base);

            // Then
            assertThat(result.maxWaitDuration()).isEqualTo(Duration.ofSeconds(2));
            assertThat(result.name()).isEqualTo(base.name());
            assertThat(result.maxConcurrentCalls()).isEqualTo(base.maxConcurrentCalls());
            assertThat(result.tags()).isEqualTo(base.tags());
            assertThat(result.derivedFromPreset()).isEqualTo(base.derivedFromPreset());
            assertThat(patch.touchedFields()).containsExactly(BulkheadField.MAX_WAIT_DURATION);
        }

        @Test
        void should_change_only_tags_when_only_tags_are_touched() {
            // Given
            BulkheadSnapshot base = baseSnapshot();
            BulkheadPatch patch = new BulkheadPatch();

            // When
            patch.touchTags(Set.of("critical"));
            BulkheadSnapshot result = patch.applyTo(base);

            // Then
            assertThat(result.tags()).containsExactly("critical");
            assertThat(result.name()).isEqualTo(base.name());
            assertThat(result.maxConcurrentCalls()).isEqualTo(base.maxConcurrentCalls());
            assertThat(result.maxWaitDuration()).isEqualTo(base.maxWaitDuration());
            assertThat(result.derivedFromPreset()).isEqualTo(base.derivedFromPreset());
            assertThat(patch.touchedFields()).containsExactly(BulkheadField.TAGS);
        }

        @Test
        void should_change_only_derived_from_preset_when_only_that_field_is_touched() {
            // Given
            BulkheadSnapshot base = baseSnapshot();
            BulkheadPatch patch = new BulkheadPatch();

            // When
            patch.touchDerivedFromPreset("protective");
            BulkheadSnapshot result = patch.applyTo(base);

            // Then
            assertThat(result.derivedFromPreset()).isEqualTo("protective");
            assertThat(result.name()).isEqualTo(base.name());
            assertThat(result.maxConcurrentCalls()).isEqualTo(base.maxConcurrentCalls());
            assertThat(result.maxWaitDuration()).isEqualTo(base.maxWaitDuration());
            assertThat(result.tags()).isEqualTo(base.tags());
            assertThat(patch.touchedFields()).containsExactly(BulkheadField.DERIVED_FROM_PRESET);
        }

        @Test
        void should_allow_clearing_the_preset_label_via_explicit_null_touch() {
            // What is to be tested: that touching derivedFromPreset with null produces a snapshot
            // whose preset is null. Why: clarification 3 in REFACTORING.md says no clearPreset()
            // setter is needed — touching the field with null is the canonical way to reset it.
            // Why important: format adapters (YAML, JSON) may want to remove a preset reference
            // explicitly; the touch+null path is their entry point.

            // Given
            BulkheadSnapshot base = baseSnapshot();   // derivedFromPreset == "balanced"
            BulkheadPatch patch = new BulkheadPatch();

            // When
            patch.touchDerivedFromPreset(null);
            BulkheadSnapshot result = patch.applyTo(base);

            // Then
            assertThat(result.derivedFromPreset()).isNull();
        }
    }

    @Nested
    @DisplayName("multi-field touches")
    class MultiFieldTouches {

        @Test
        void should_change_every_field_when_every_field_is_touched() {
            // Given
            BulkheadSnapshot base = baseSnapshot();
            BulkheadPatch patch = new BulkheadPatch();

            // When
            patch.touchName("renamed");
            patch.touchMaxConcurrentCalls(50);
            patch.touchMaxWaitDuration(Duration.ofMillis(250));
            patch.touchTags(Set.of("a", "b"));
            patch.touchDerivedFromPreset("permissive");
            patch.touchEvents(BulkheadEventConfig.allEnabled());
            BulkheadSnapshot result = patch.applyTo(base);

            // Then
            assertThat(result.name()).isEqualTo("renamed");
            assertThat(result.maxConcurrentCalls()).isEqualTo(50);
            assertThat(result.maxWaitDuration()).isEqualTo(Duration.ofMillis(250));
            assertThat(result.tags()).containsExactlyInAnyOrder("a", "b");
            assertThat(result.derivedFromPreset()).isEqualTo("permissive");
            assertThat(result.events()).isEqualTo(BulkheadEventConfig.allEnabled());
            assertThat(patch.touchedFields())
                    .containsExactlyInAnyOrder(BulkheadField.values());
        }

        @Test
        void should_overwrite_an_earlier_touch_with_a_later_one() {
            // What is to be tested: that calling the same touch method twice records the most
            // recent value. Why: the DSL builder may chain a preset (which sets several fields)
            // followed by individual setters that override one of them — the override must win.
            // Why important: the preset-then-customize discipline (ADR-027 strategy A) depends on
            // this behaviour.

            // Given
            BulkheadSnapshot base = baseSnapshot();
            BulkheadPatch patch = new BulkheadPatch();

            // When
            patch.touchMaxConcurrentCalls(20);
            patch.touchMaxConcurrentCalls(50);
            BulkheadSnapshot result = patch.applyTo(base);

            // Then
            assertThat(result.maxConcurrentCalls()).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("touchedFields immutability")
    class TouchedFieldsImmutability {

        @Test
        void should_return_an_unmodifiable_view() {
            // Given
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchName("x");

            // When / Then
            Set<BulkheadField> view = patch.touchedFields();
            assertThat(view).containsExactly(BulkheadField.NAME);

            // Mutation attempts must fail
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> view.add(BulkheadField.MAX_CONCURRENT_CALLS))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void should_decouple_each_call_from_subsequent_touches() {
            // Given
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchName("x");
            Set<BulkheadField> first = patch.touchedFields();

            // When
            patch.touchMaxConcurrentCalls(5);
            Set<BulkheadField> second = patch.touchedFields();

            // Then
            assertThat(first).containsExactly(BulkheadField.NAME);
            assertThat(second).containsExactlyInAnyOrder(
                    BulkheadField.NAME, BulkheadField.MAX_CONCURRENT_CALLS);
        }
    }

    @Nested
    @DisplayName("isTouched")
    class IsTouched {

        @Test
        void should_reject_a_null_field() {
            // Given
            BulkheadPatch patch = new BulkheadPatch();

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> patch.isTouched(null))
                    .withMessageContaining("field");
        }

        @Test
        void should_return_true_only_for_touched_fields() {
            // Given
            BulkheadPatch patch = new BulkheadPatch();

            // When
            patch.touchMaxConcurrentCalls(5);

            // Then
            assertThat(patch.isTouched(BulkheadField.MAX_CONCURRENT_CALLS)).isTrue();
            assertThat(patch.isTouched(BulkheadField.NAME)).isFalse();
            assertThat(patch.isTouched(BulkheadField.MAX_WAIT_DURATION)).isFalse();
            assertThat(patch.isTouched(BulkheadField.TAGS)).isFalse();
            assertThat(patch.isTouched(BulkheadField.DERIVED_FROM_PRESET)).isFalse();
        }
    }

    @Nested
    @DisplayName("invalid touched values propagate to applyTo")
    class InvalidValues {

        @Test
        void should_propagate_invariant_violations_through_the_snapshot_compact_constructor() {
            // What is to be tested: that invalid touch values do not silently apply — instead,
            // the snapshot's compact constructor rejects them when applyTo runs. Why: the patch
            // itself does not validate values (validation lives at class 1 in setters and
            // class 2 in compact constructors per ADR-027). The patch is a transport.
            // Why important: this guarantees no path through the patch can produce an invalid
            // snapshot — every applied snapshot has been validated.

            // Given
            BulkheadSnapshot base = baseSnapshot();
            BulkheadPatch patch = new BulkheadPatch();

            // When
            patch.touchMaxConcurrentCalls(-1);

            // Then
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> patch.applyTo(base))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxConcurrentCalls must be positive");
        }
    }
}
