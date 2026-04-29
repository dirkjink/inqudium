package eu.inqudium.config.patch;

import eu.inqudium.config.lifecycle.ComponentField;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadField;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.CoDelStrategyConfig;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
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
                BulkheadEventConfig.disabled(),
                new SemaphoreStrategyConfig());
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

        @Test
        void should_touch_strategy_and_apply_it_to_the_base_snapshot() {
            // What is to be tested: touchStrategy records STRATEGY in touchedFields(), the
            // touched value appears in proposedValues(), and applyTo emits a snapshot whose
            // strategy() is the new value while every other field inherits from the base.
            // Why important: 2.10.A introduces the field on the patch alongside snapshot —
            // until 2.10.B wires the materialization, this is the only end-to-end pin that the
            // strategy can travel through the patch path.

            // Given
            BulkheadSnapshot base = baseSnapshot();
            BulkheadPatch patch = new BulkheadPatch();
            CoDelStrategyConfig codel = new CoDelStrategyConfig(
                    java.time.Duration.ofMillis(50), java.time.Duration.ofMillis(100));

            // When
            patch.touchStrategy(codel);
            BulkheadSnapshot result = patch.applyTo(base);

            // Then
            assertThat(patch.touchedFields()).containsExactly(BulkheadField.STRATEGY);
            assertThat(patch.proposedValues()).containsEntry(BulkheadField.STRATEGY, codel);
            assertThat(result.strategy()).isSameAs(codel);
            // Every other field inherits from the base snapshot
            assertThat(result.name()).isEqualTo(base.name());
            assertThat(result.maxConcurrentCalls()).isEqualTo(base.maxConcurrentCalls());
            assertThat(result.maxWaitDuration()).isEqualTo(base.maxWaitDuration());
            assertThat(result.tags()).isEqualTo(base.tags());
            assertThat(result.derivedFromPreset()).isEqualTo(base.derivedFromPreset());
            assertThat(result.events()).isEqualTo(base.events());
        }

        @Test
        void should_inherit_strategy_from_base_when_not_touched() {
            // Pinning the inverse of the previous test: a patch that does not call
            // touchStrategy must leave the base snapshot's strategy in place. Confirms the
            // applyTo branching reads base.strategy() on the untouched path.

            BulkheadSnapshot base = baseSnapshot();
            BulkheadPatch patch = new BulkheadPatch();

            patch.touchMaxConcurrentCalls(99);
            BulkheadSnapshot result = patch.applyTo(base);

            assertThat(patch.touchedFields()).doesNotContain(BulkheadField.STRATEGY);
            assertThat(result.strategy()).isSameAs(base.strategy());
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
            SemaphoreStrategyConfig newStrategy = new SemaphoreStrategyConfig();

            // When
            patch.touchName("renamed");
            patch.touchMaxConcurrentCalls(50);
            patch.touchMaxWaitDuration(Duration.ofMillis(250));
            patch.touchTags(Set.of("a", "b"));
            patch.touchDerivedFromPreset("permissive");
            patch.touchEvents(BulkheadEventConfig.allEnabled());
            patch.touchStrategy(newStrategy);
            BulkheadSnapshot result = patch.applyTo(base);

            // Then
            assertThat(result.name()).isEqualTo("renamed");
            assertThat(result.maxConcurrentCalls()).isEqualTo(50);
            assertThat(result.maxWaitDuration()).isEqualTo(Duration.ofMillis(250));
            assertThat(result.tags()).containsExactlyInAnyOrder("a", "b");
            assertThat(result.derivedFromPreset()).isEqualTo("permissive");
            assertThat(result.events()).isEqualTo(BulkheadEventConfig.allEnabled());
            assertThat(result.strategy()).isSameAs(newStrategy);
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
    @DisplayName("proposedValues")
    class ProposedValues {

        @Test
        void should_be_empty_for_an_empty_patch() {
            // What is to be tested: that an untouched patch produces an empty map. Why: the
            // dispatcher derives the ChangeRequest's proposed-value map directly from this method;
            // an untouched patch must surface as "no proposals" rather than as a map full of
            // nulls or default values that listeners might misread.
            // Why important: a listener inspecting the request must not see fields the user did
            // not intend to change.

            // Given
            BulkheadPatch patch = new BulkheadPatch();

            // When
            Map<ComponentField, Object> values = patch.proposedValues();

            // Then
            assertThat(values).isEmpty();
        }

        @Test
        void should_contain_a_single_entry_when_only_one_field_is_touched() {
            // Given
            BulkheadPatch patch = new BulkheadPatch();

            // When
            patch.touchMaxConcurrentCalls(42);
            Map<ComponentField, Object> values = patch.proposedValues();

            // Then
            assertThat(values).containsOnly(
                    org.assertj.core.api.Assertions.entry(
                            BulkheadField.MAX_CONCURRENT_CALLS, 42));
        }

        @Test
        void should_contain_every_touched_field_when_all_fields_are_touched() {
            // Given
            BulkheadPatch patch = new BulkheadPatch();
            BulkheadEventConfig events = BulkheadEventConfig.allEnabled();
            Set<String> tags = Set.of("a", "b");
            SemaphoreStrategyConfig strategy = new SemaphoreStrategyConfig();

            // When
            patch.touchName("renamed");
            patch.touchMaxConcurrentCalls(50);
            patch.touchMaxWaitDuration(Duration.ofMillis(250));
            patch.touchTags(tags);
            patch.touchDerivedFromPreset("permissive");
            patch.touchEvents(events);
            patch.touchStrategy(strategy);
            Map<ComponentField, Object> values = patch.proposedValues();

            // Then
            assertThat(values).hasSize(BulkheadField.values().length);
            assertThat(values).containsEntry(BulkheadField.NAME, "renamed");
            assertThat(values).containsEntry(BulkheadField.MAX_CONCURRENT_CALLS, 50);
            assertThat(values).containsEntry(
                    BulkheadField.MAX_WAIT_DURATION, Duration.ofMillis(250));
            assertThat(values).containsEntry(BulkheadField.TAGS, tags);
            assertThat(values).containsEntry(BulkheadField.DERIVED_FROM_PRESET, "permissive");
            assertThat(values).containsEntry(BulkheadField.EVENTS, events);
            assertThat(values).containsEntry(BulkheadField.STRATEGY, strategy);
        }

        @Test
        void should_omit_untouched_fields() {
            // What is to be tested: that fields the patch did not touch do not appear in the map,
            // not even with a default or null value. Why: clarification 5 in REFACTORING.md is
            // explicit — proposedValues exposes proposals, not the full snapshot; the dispatcher
            // pairs absent keys with the base snapshot's value, not with null.
            // Why important: a listener treating "key absent" as "field set to null" would
            // misjudge every patch that only touches a subset of fields.

            // Given
            BulkheadPatch patch = new BulkheadPatch();

            // When
            patch.touchName("x");
            patch.touchMaxConcurrentCalls(5);
            Map<ComponentField, Object> values = patch.proposedValues();

            // Then
            assertThat(values.keySet()).containsExactlyInAnyOrder(
                    BulkheadField.NAME, BulkheadField.MAX_CONCURRENT_CALLS);
            assertThat(values).doesNotContainKey(BulkheadField.MAX_WAIT_DURATION);
            assertThat(values).doesNotContainKey(BulkheadField.TAGS);
            assertThat(values).doesNotContainKey(BulkheadField.DERIVED_FROM_PRESET);
            assertThat(values).doesNotContainKey(BulkheadField.EVENTS);
        }

        @Test
        void should_carry_a_null_value_for_a_touched_field_explicitly_set_to_null() {
            // What is to be tested: that touchDerivedFromPreset(null) — the canonical preset-clear
            // operation — surfaces in proposedValues as an entry whose value is null. Why: a
            // listener inspecting "the user is clearing the preset" needs to see the touched key
            // with a null value, not the preset's previous label inherited from the base.
            // Why important: distinguishes "untouched" (key absent) from "touched to null" (key
            // present with null value).

            // Given
            BulkheadPatch patch = new BulkheadPatch();

            // When
            patch.touchDerivedFromPreset(null);
            Map<ComponentField, Object> values = patch.proposedValues();

            // Then
            assertThat(values).containsKey(BulkheadField.DERIVED_FROM_PRESET);
            assertThat(values.get(BulkheadField.DERIVED_FROM_PRESET)).isNull();
        }

        @Test
        void should_return_an_unmodifiable_map() {
            // Given
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchName("x");

            // When
            Map<ComponentField, Object> values = patch.proposedValues();

            // Then
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> values.put(BulkheadField.MAX_CONCURRENT_CALLS, 1))
                    .isInstanceOf(UnsupportedOperationException.class);
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
