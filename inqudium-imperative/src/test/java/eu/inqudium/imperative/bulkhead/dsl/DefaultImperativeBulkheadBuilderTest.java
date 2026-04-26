package eu.inqudium.imperative.bulkhead.dsl;

import eu.inqudium.config.patch.BulkheadPatch;
import eu.inqudium.config.snapshot.BulkheadField;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultImperativeBulkheadBuilder")
class DefaultImperativeBulkheadBuilderTest {

    /**
     * A "system default" snapshot used as the apply-base for tests that build a snapshot from
     * the patch. The DSL's job is to configure the patch; producing a final snapshot is the
     * patch's job (and ultimately the runtime container's). Using a fixed default makes the
     * inherited fields predictable.
     */
    private static BulkheadSnapshot systemDefault() {
        return new BulkheadSnapshot(
                "default-name",
                25,
                Duration.ofMillis(100),
                Set.of(),
                null);
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void should_touch_the_name_on_the_underlying_patch() {
            // Given / When
            DefaultImperativeBulkheadBuilder builder =
                    new DefaultImperativeBulkheadBuilder("inventory");

            // Then
            BulkheadPatch patch = builder.toPatch();
            assertThat(patch.isTouched(BulkheadField.NAME)).isTrue();
            assertThat(patch.applyTo(systemDefault()).name()).isEqualTo("inventory");
        }

        @Test
        void should_reject_a_null_name() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new DefaultImperativeBulkheadBuilder(null))
                    .withMessageContaining("name");
        }

        @Test
        void should_reject_a_blank_name() {
            // Given / When / Then
            assertThatThrownBy(() -> new DefaultImperativeBulkheadBuilder("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name must not be blank");
        }
    }

    @Nested
    @DisplayName("class-1 setter validation")
    class Class1Validation {

        @Test
        void should_reject_zero_max_concurrent_calls() {
            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When / Then
            assertThatThrownBy(() -> b.maxConcurrentCalls(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxConcurrentCalls must be positive");
        }

        @Test
        void should_reject_a_negative_max_concurrent_calls() {
            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When / Then
            assertThatThrownBy(() -> b.maxConcurrentCalls(-3))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("got: -3");
        }

        @Test
        void should_reject_a_null_max_wait_duration() {
            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> b.maxWaitDuration(null))
                    .withMessageContaining("maxWaitDuration");
        }

        @Test
        void should_reject_a_negative_max_wait_duration() {
            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When / Then
            assertThatThrownBy(() -> b.maxWaitDuration(Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxWaitDuration must not be negative");
        }

        @Test
        void should_reject_a_null_tags_varargs_array() {
            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> b.tags((String[]) null))
                    .withMessageContaining("tags");
        }

        @Test
        void should_reject_a_null_tag_element_in_varargs() {
            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> b.tags("a", null, "b"))
                    .withMessageContaining("tag element");
        }

        @Test
        void should_reject_a_null_tag_set() {
            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> b.tags((Set<String>) null))
                    .withMessageContaining("tags");
        }
    }

    @Nested
    @DisplayName("preset values")
    class PresetValues {

        @Test
        void protective_should_set_fail_fast_baseline() {
            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When
            b.protective();
            BulkheadSnapshot result = b.toPatch().applyTo(systemDefault());

            // Then
            assertThat(result.maxConcurrentCalls()).isEqualTo(10);
            assertThat(result.maxWaitDuration()).isEqualTo(Duration.ZERO);
            assertThat(result.derivedFromPreset()).isEqualTo("protective");
        }

        @Test
        void balanced_should_set_production_default_baseline() {
            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When
            b.balanced();
            BulkheadSnapshot result = b.toPatch().applyTo(systemDefault());

            // Then
            assertThat(result.maxConcurrentCalls()).isEqualTo(50);
            assertThat(result.maxWaitDuration()).isEqualTo(Duration.ofMillis(500));
            assertThat(result.derivedFromPreset()).isEqualTo("balanced");
        }

        @Test
        void permissive_should_set_generous_baseline() {
            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When
            b.permissive();
            BulkheadSnapshot result = b.toPatch().applyTo(systemDefault());

            // Then
            assertThat(result.maxConcurrentCalls()).isEqualTo(200);
            assertThat(result.maxWaitDuration()).isEqualTo(Duration.ofSeconds(5));
            assertThat(result.derivedFromPreset()).isEqualTo("permissive");
        }

        @Test
        void presets_should_not_touch_tags() {
            // What is to be tested: that presets leave the tags field untouched, so users can
            // call .balanced().tags(...) and have both the preset values AND their tags applied.
            // Why successful: the patch's TAGS bit must not be set after a preset call, so
            // applyTo inherits the base snapshot's tags.
            // Why important: user expectation is that presets cover concurrency/wait baselines,
            // not operational metadata like tags.

            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When
            b.balanced();

            // Then
            assertThat(b.toPatch().isTouched(BulkheadField.TAGS)).isFalse();
        }
    }

    @Nested
    @DisplayName("preset-then-customize discipline")
    class PresetThenCustomize {

        @Test
        void should_allow_individual_setter_after_preset() {
            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When
            b.balanced().maxConcurrentCalls(75);
            BulkheadSnapshot result = b.toPatch().applyTo(systemDefault());

            // Then
            assertThat(result.maxConcurrentCalls()).isEqualTo(75);
            assertThat(result.maxWaitDuration()).isEqualTo(Duration.ofMillis(500));
            assertThat(result.derivedFromPreset()).isEqualTo("balanced");
        }

        @Test
        void should_throw_when_a_preset_is_called_after_an_individual_setter() {
            // What is to be tested: that ADR-027 strategy A — preset-then-customize discipline —
            // is enforced. Calling a preset after a setter would silently overwrite the user's
            // configuration; making it a runtime error forces the user to express their
            // intention in the right order.
            // Why successful: the IllegalStateException carries the documented message that
            // points the user to the correct order.
            // Why important: silent overwrite is the most common DSL misuse; failing loudly is
            // the ergonomic win.

            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When
            b.maxConcurrentCalls(15);

            // Then
            assertThatThrownBy(b::balanced)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot apply a preset after individual setters")
                    .hasMessageContaining("Presets are baselines: call them first");
        }

        @Test
        void should_throw_for_protective_after_setter() {
            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When
            b.maxWaitDuration(Duration.ofMillis(200));

            // Then
            assertThatThrownBy(b::protective)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void should_throw_for_permissive_after_setter() {
            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When
            b.tags("payment");

            // Then
            assertThatThrownBy(b::permissive)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void should_allow_chaining_two_presets_with_the_last_one_winning() {
            // Multiple presets on the same builder is an unusual but not invalid sequence —
            // the customized flag is not set by presets, so the second preset's values overwrite
            // the first's. ADR-027 strategy A only constrains the customize-then-preset case.
            // Documenting this current behaviour pins the contract.

            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When
            b.protective().permissive();
            BulkheadSnapshot result = b.toPatch().applyTo(systemDefault());

            // Then
            assertThat(result.maxConcurrentCalls()).isEqualTo(200);
            assertThat(result.derivedFromPreset()).isEqualTo("permissive");
        }
    }

    @Nested
    @DisplayName("touch-tracking semantics")
    class TouchTracking {

        @Test
        void individual_setters_should_only_touch_their_own_field() {
            // What is to be tested: clarification 3 in REFACTORING.md — individual setters do
            // NOT touch derivedFromPreset, so a hot patch that only calls maxConcurrentCalls(15)
            // inherits the previous preset label.
            // Why successful: after one setter, only that field is in touchedFields().
            // Why important: class-3 rules like BULKHEAD_PROTECTIVE_WITH_LONG_WAIT must
            // continue to fire after hot updates.

            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When
            b.maxConcurrentCalls(15);

            // Then
            assertThat(b.toPatch().touchedFields()).containsExactlyInAnyOrder(
                    BulkheadField.NAME,
                    BulkheadField.MAX_CONCURRENT_CALLS);
        }

        @Test
        void presets_should_touch_max_concurrent_calls_max_wait_duration_and_derived_from_preset() {
            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When
            b.balanced();

            // Then
            assertThat(b.toPatch().touchedFields()).containsExactlyInAnyOrder(
                    BulkheadField.NAME,
                    BulkheadField.MAX_CONCURRENT_CALLS,
                    BulkheadField.MAX_WAIT_DURATION,
                    BulkheadField.DERIVED_FROM_PRESET);
        }

        @Test
        void preset_then_setter_should_keep_the_setter_value_for_overlapping_fields() {
            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When
            b.protective().maxConcurrentCalls(75);
            BulkheadSnapshot result = b.toPatch().applyTo(systemDefault());

            // Then — setter wins for the field they share, preset wins for fields it alone
            // touched (maxWaitDuration, derivedFromPreset).
            assertThat(result.maxConcurrentCalls()).isEqualTo(75);
            assertThat(result.maxWaitDuration()).isEqualTo(Duration.ZERO);
            assertThat(result.derivedFromPreset()).isEqualTo("protective");
        }

        @Test
        void tags_via_set_should_be_defensively_copied() {
            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");
            Set<String> mutable = new java.util.HashSet<>();
            mutable.add("payment");

            // When
            b.tags(mutable);
            mutable.add("critical");
            BulkheadSnapshot result = b.toPatch().applyTo(systemDefault());

            // Then
            assertThat(result.tags()).containsExactly("payment");
        }
    }

    @Nested
    @DisplayName("fluent return")
    class FluentReturn {

        @Test
        void every_method_should_return_the_same_builder_instance() {
            // Given
            DefaultImperativeBulkheadBuilder b = new DefaultImperativeBulkheadBuilder("x");

            // When / Then
            assertThat(b.maxConcurrentCalls(5)).isSameAs(b);
            assertThat(b.maxWaitDuration(Duration.ZERO)).isSameAs(b);
            assertThat(b.tags("a")).isSameAs(b);
            assertThat(b.tags(Set.of("b"))).isSameAs(b);

            DefaultImperativeBulkheadBuilder b2 = new DefaultImperativeBulkheadBuilder("y");
            assertThat(b2.protective()).isSameAs(b2);

            DefaultImperativeBulkheadBuilder b3 = new DefaultImperativeBulkheadBuilder("z");
            assertThat(b3.balanced()).isSameAs(b3);

            DefaultImperativeBulkheadBuilder b4 = new DefaultImperativeBulkheadBuilder("w");
            assertThat(b4.permissive()).isSameAs(b4);
        }
    }
}
