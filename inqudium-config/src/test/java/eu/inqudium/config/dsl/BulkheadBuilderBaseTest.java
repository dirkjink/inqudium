package eu.inqudium.config.dsl;

import eu.inqudium.config.patch.BulkheadPatch;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadField;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BulkheadBuilderBase")
class BulkheadBuilderBaseTest {

    /**
     * Test-only concrete subclass. The base class is generic over a paradigm tag; we use the
     * imperative tag because it is the only currently-permitted {@code ParadigmTag}, but the
     * tests below exercise only paradigm-agnostic behaviour. The corresponding
     * paradigm-specific tests live next to the concrete builder in
     * {@code DefaultImperativeBulkheadBuilderTest}.
     */
    private static final class TestBuilder extends BulkheadBuilderBase<ImperativeTag> {
        TestBuilder(String name) {
            super(name);
        }
    }

    /**
     * The "system default" snapshot used as the apply-base. The DSL's job is to configure the
     * patch; the patch's job is to produce a snapshot when applied to a base. A fixed default
     * keeps inherited fields predictable.
     */
    private static BulkheadSnapshot systemDefault() {
        return new BulkheadSnapshot(
                "default-name",
                25,
                Duration.ofMillis(100),
                Set.of(),
                null,
                BulkheadEventConfig.disabled());
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void should_touch_the_name_on_the_underlying_patch() {
            // Given / When
            TestBuilder builder = new TestBuilder("inventory");

            // Then
            BulkheadPatch patch = builder.toPatch();
            assertThat(patch.isTouched(BulkheadField.NAME)).isTrue();
            assertThat(patch.applyTo(systemDefault()).name()).isEqualTo("inventory");
        }

        @Test
        void should_reject_a_null_name() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new TestBuilder(null))
                    .withMessageContaining("name");
        }

        @Test
        void should_reject_a_blank_name() {
            // Given / When / Then
            assertThatThrownBy(() -> new TestBuilder("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name must not be blank");
        }

        @Test
        void should_reject_an_empty_name() {
            // Given / When / Then
            assertThatThrownBy(() -> new TestBuilder(""))
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
            TestBuilder b = new TestBuilder("x");

            // When / Then
            assertThatThrownBy(() -> b.maxConcurrentCalls(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxConcurrentCalls must be positive");
        }

        @Test
        void should_reject_a_negative_max_concurrent_calls() {
            // Given
            TestBuilder b = new TestBuilder("x");

            // When / Then
            assertThatThrownBy(() -> b.maxConcurrentCalls(-3))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("got: -3");
        }

        @Test
        void should_reject_a_null_max_wait_duration() {
            // Given
            TestBuilder b = new TestBuilder("x");

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> b.maxWaitDuration(null))
                    .withMessageContaining("maxWaitDuration");
        }

        @Test
        void should_reject_a_negative_max_wait_duration() {
            // Given
            TestBuilder b = new TestBuilder("x");

            // When / Then
            assertThatThrownBy(() -> b.maxWaitDuration(Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxWaitDuration must not be negative");
        }

        @Test
        void should_reject_a_null_tags_varargs_array() {
            // Given
            TestBuilder b = new TestBuilder("x");

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> b.tags((String[]) null))
                    .withMessageContaining("tags");
        }

        @Test
        void should_reject_a_null_tag_element_in_varargs() {
            // Given
            TestBuilder b = new TestBuilder("x");

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> b.tags("a", null, "b"))
                    .withMessageContaining("tag element");
        }

        @Test
        void should_reject_a_null_tag_set() {
            // Given
            TestBuilder b = new TestBuilder("x");

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> b.tags((Set<String>) null))
                    .withMessageContaining("tags");
        }

        @Test
        void should_reject_a_null_events_config() {
            // Given
            TestBuilder b = new TestBuilder("x");

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> b.events(null))
                    .withMessageContaining("events");
        }
    }

    @Nested
    @DisplayName("preset values")
    class PresetValues {

        @Test
        void protective_should_set_fail_fast_baseline() {
            // Given
            TestBuilder b = new TestBuilder("x");

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
            TestBuilder b = new TestBuilder("x");

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
            TestBuilder b = new TestBuilder("x");

            // When
            b.permissive();
            BulkheadSnapshot result = b.toPatch().applyTo(systemDefault());

            // Then
            assertThat(result.maxConcurrentCalls()).isEqualTo(200);
            assertThat(result.maxWaitDuration()).isEqualTo(Duration.ofSeconds(5));
            assertThat(result.derivedFromPreset()).isEqualTo("permissive");
        }

        @Test
        void presets_should_not_touch_events() {
            // What is to be tested: that presets leave the events field untouched, just like
            // tags. Why: per ADR-030 events are application metadata, not preset territory —
            // a preset establishing observability defaults would be a hidden behavioural
            // change at preset time.
            // Why important: users reach for presets to set concurrency/wait baselines; an
            // implicit events-on flag from a preset would surprise them.

            // Given
            TestBuilder b = new TestBuilder("x");

            // When
            b.balanced();

            // Then — the EVENTS bit comes from the constructor's defaulting touch, not from
            // the preset. The preset itself does not set customized=true, so a follow-up
            // events(...) call would still work without violating preset-then-customize. The
            // resulting snapshot keeps disabled() because the preset did not override.
            BulkheadSnapshot result = b.toPatch().applyTo(systemDefault());
            assertThat(result.events()).isEqualTo(BulkheadEventConfig.disabled());
        }

        @Test
        void should_default_events_to_disabled() {
            // Given / When — no explicit events() call; constructor default applies
            TestBuilder b = new TestBuilder("x");
            BulkheadSnapshot result = b.toPatch().applyTo(systemDefault());

            // Then
            assertThat(result.events()).isEqualTo(BulkheadEventConfig.disabled());
        }

        @Test
        void events_setter_should_replace_the_default() {
            // Given
            TestBuilder b = new TestBuilder("x");

            // When
            b.events(BulkheadEventConfig.allEnabled());
            BulkheadSnapshot result = b.toPatch().applyTo(systemDefault());

            // Then
            assertThat(result.events()).isEqualTo(BulkheadEventConfig.allEnabled());
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
            TestBuilder b = new TestBuilder("x");

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
            TestBuilder b = new TestBuilder("x");

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
            // What is to be tested: ADR-027 strategy A — preset-then-customize discipline.
            // Calling a preset after a setter would silently overwrite the user's
            // configuration; making it a runtime error forces the user to express their
            // intention in the right order.
            // Why successful: the IllegalStateException carries the documented message that
            // points the user to the correct order.
            // Why important: silent overwrite is the most common DSL misuse; failing loudly is
            // the ergonomic win.

            // Given
            TestBuilder b = new TestBuilder("x");

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
            TestBuilder b = new TestBuilder("x");

            // When
            b.maxWaitDuration(Duration.ofMillis(200));

            // Then
            assertThatThrownBy(b::protective)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void should_throw_for_permissive_after_setter() {
            // Given
            TestBuilder b = new TestBuilder("x");

            // When
            b.tags("payment");

            // Then
            assertThatThrownBy(b::permissive)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void events_call_should_engage_the_preset_then_customize_guard() {
            // What is to be tested: that calling events(...) counts as customization just
            // like the other individual setters. Calling a preset after events(...) must
            // therefore throw.
            // Why successful: the IllegalStateException carries the preset-ordering message
            // even though the only customization was an events() call.
            // Why important: the constructor's defaulting touch on EVENTS does not count as
            // customization (it is internal scaffolding to satisfy the snapshot's non-null
            // events invariant); only the user-facing setter does.

            // Given
            TestBuilder b = new TestBuilder("x");

            // When
            b.events(BulkheadEventConfig.allEnabled());

            // Then
            assertThatThrownBy(b::balanced)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot apply a preset");
        }

        @Test
        void preset_then_events_should_keep_both() {
            // Given
            TestBuilder b = new TestBuilder("x");

            // When
            b.balanced().events(BulkheadEventConfig.allEnabled());
            BulkheadSnapshot result = b.toPatch().applyTo(systemDefault());

            // Then — preset set the limits, events setter applied after the preset wins for
            // the events field.
            assertThat(result.maxConcurrentCalls()).isEqualTo(50);
            assertThat(result.derivedFromPreset()).isEqualTo("balanced");
            assertThat(result.events()).isEqualTo(BulkheadEventConfig.allEnabled());
        }

        @Test
        void should_allow_chaining_two_presets_with_the_last_one_winning() {
            // Multiple presets on the same builder is an unusual but not invalid sequence —
            // the customized flag is not set by presets, so the second preset's values overwrite
            // the first's. ADR-027 strategy A only constrains the customize-then-preset case.
            // Documenting this current behaviour pins the contract.

            // Given
            TestBuilder b = new TestBuilder("x");

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
            // Why successful: after one setter, only that field plus the constructor-touched
            // NAME and EVENTS (the latter touched by the base class with the disabled() default
            // so the snapshot's non-null events invariant holds — see ADR-030) appear in
            // touchedFields().
            // Why important: class-3 rules like BULKHEAD_PROTECTIVE_WITH_LONG_WAIT must
            // continue to fire after hot updates.

            // Given
            TestBuilder b = new TestBuilder("x");

            // When
            b.maxConcurrentCalls(15);

            // Then
            assertThat(b.toPatch().touchedFields()).containsExactlyInAnyOrder(
                    BulkheadField.NAME,
                    BulkheadField.MAX_CONCURRENT_CALLS,
                    BulkheadField.EVENTS);
        }

        @Test
        void presets_should_touch_max_concurrent_calls_max_wait_duration_and_derived_from_preset() {
            // Given
            TestBuilder b = new TestBuilder("x");

            // When
            b.balanced();

            // Then — preset touches the three preset fields, NAME and EVENTS come from the
            // constructor, TAGS stays untouched (analogous to the events decision: presets
            // do not own application metadata).
            assertThat(b.toPatch().touchedFields()).containsExactlyInAnyOrder(
                    BulkheadField.NAME,
                    BulkheadField.MAX_CONCURRENT_CALLS,
                    BulkheadField.MAX_WAIT_DURATION,
                    BulkheadField.DERIVED_FROM_PRESET,
                    BulkheadField.EVENTS);
        }

        @Test
        void preset_then_setter_should_keep_the_setter_value_for_overlapping_fields() {
            // Given
            TestBuilder b = new TestBuilder("x");

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
            TestBuilder b = new TestBuilder("x");
            Set<String> mutable = new HashSet<>();
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
    @DisplayName("tag varargs duplicate handling")
    class DuplicateTagHandling {

        @Test
        void should_silently_dedupe_duplicate_tags_in_varargs() {
            // What is to be tested: that calling tags("a", "b", "a") produces a snapshot with
            // tags {"a", "b"} rather than throwing on the duplicate.
            //
            // Why this is the right behaviour: a fluent builder should be construction-friendly.
            // Tags form a Set semantically — duplicates carry no information. Throwing on them
            // would force callers to dedupe their inputs before invoking the DSL, which is not
            // a service the user expects from a builder. The cost is small (one HashSet
            // allocation per tags() call); the benefit is that the builder accepts whatever
            // collection the user happens to have on hand without ceremony.
            //
            // The Set<String> overload does not need this treatment because Set already
            // disallows duplicates by definition; it is only varargs that admit them.

            // Given
            TestBuilder b = new TestBuilder("x");

            // When
            b.tags("payment", "critical", "payment", "critical");
            BulkheadSnapshot result = b.toPatch().applyTo(systemDefault());

            // Then
            assertThat(result.tags()).containsExactlyInAnyOrder("payment", "critical");
        }
    }

    @Nested
    @DisplayName("fluent return")
    class FluentReturn {

        @Test
        void every_method_should_return_the_same_builder_instance() {
            // Given
            TestBuilder b = new TestBuilder("x");

            // When / Then
            assertThat(b.maxConcurrentCalls(5)).isSameAs(b);
            assertThat(b.maxWaitDuration(Duration.ZERO)).isSameAs(b);
            assertThat(b.tags("a")).isSameAs(b);
            assertThat(b.tags(Set.of("b"))).isSameAs(b);

            TestBuilder b2 = new TestBuilder("y");
            assertThat(b2.protective()).isSameAs(b2);

            TestBuilder b3 = new TestBuilder("z");
            assertThat(b3.balanced()).isSameAs(b3);

            TestBuilder b4 = new TestBuilder("w");
            assertThat(b4.permissive()).isSameAs(b4);
        }
    }
}
