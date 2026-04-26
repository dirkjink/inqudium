package eu.inqudium.imperative.runtime;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.ImperativeBulkhead;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.validation.ApplyOutcome;
import eu.inqudium.config.validation.BuildReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("runtime.update")
class RuntimeUpdateTest {

    @Nested
    @DisplayName("patch existing")
    class PatchExisting {

        @Test
        void should_patch_an_existing_bulkhead_and_report_PATCHED() {
            // What is to be tested: that runtime.update routes a patch to the existing bulkhead's
            // live container and the snapshot reflects the new value.
            // Why successful: bulkhead.snapshot().maxConcurrentCalls() returns the patched value
            // immediately after update returns.
            // Why important: this is the core promise of runtime mutability — adjust limits
            // during traffic spikes without restart.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.balanced().maxConcurrentCalls(15)))
                    .build()) {

                ImperativeBulkhead bh = runtime.imperative().bulkhead("inventory");
                assertThat(bh.snapshot().maxConcurrentCalls()).isEqualTo(15);

                // When
                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(40))));

                // Then
                assertThat(bh.snapshot().maxConcurrentCalls()).isEqualTo(40);
                assertThat(report.componentOutcomes())
                        .containsEntry("inventory", ApplyOutcome.PATCHED);
                assertThat(report.isSuccess()).isTrue();
            }
        }

        @Test
        void should_preserve_untouched_fields_during_patch() {
            // The "patch only what was touched" semantics extend through the runtime path.
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.balanced()))
                    .build()) {

                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(99))));

                BulkheadSnapshot snap = runtime.imperative().bulkhead("inventory").snapshot();
                assertThat(snap.maxConcurrentCalls()).isEqualTo(99);
                assertThat(snap.maxWaitDuration())
                        .as("balanced preset's maxWaitDuration is preserved")
                        .isEqualTo(Duration.ofMillis(500));
                assertThat(snap.derivedFromPreset())
                        .as("preset label inherited per clarification 3 in REFACTORING.md")
                        .isEqualTo("balanced");
            }
        }

        @Test
        void should_report_UNCHANGED_when_patch_is_a_no_op() {
            // What is to be tested: that an update that touches a field with the same value it
            // already has reports UNCHANGED rather than PATCHED, distinguishing "we tried but
            // there was nothing to do" from "we made changes".
            // Why important: dashboards counting PATCHED outcomes use this to track real
            // configuration churn versus idempotent no-op writes from format adapters.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.balanced().maxConcurrentCalls(15)))
                    .build()) {

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(15))));

                assertThat(report.componentOutcomes())
                        .containsEntry("inventory", ApplyOutcome.UNCHANGED);
            }
        }
    }

    @Nested
    @DisplayName("add new")
    class AddNew {

        @Test
        void should_add_a_new_bulkhead_and_report_ADDED() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("payments", b -> b.protective())));

                assertThat(report.componentOutcomes())
                        .containsEntry("payments", ApplyOutcome.ADDED);
                assertThat(runtime.imperative().bulkheadNames())
                        .containsExactlyInAnyOrder("inventory", "payments");

                ImperativeBulkhead newBulkhead = runtime.imperative().bulkhead("payments");
                assertThat(newBulkhead.snapshot().derivedFromPreset()).isEqualTo("protective");
                assertThat(newBulkhead.snapshot().maxWaitDuration()).isEqualTo(Duration.ZERO);
            }
        }

        @Test
        void should_add_a_bulkhead_to_an_initially_empty_runtime() {
            try (InqRuntime runtime = Inqudium.configure().build()) {
                assertThat(runtime.imperative().bulkheadNames()).isEmpty();

                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("first", b -> b.balanced())));

                assertThat(runtime.imperative().bulkheadNames()).containsExactly("first");
            }
        }

        @Test
        void should_make_added_bulkhead_immediately_observable_via_config_view() {
            try (InqRuntime runtime = Inqudium.configure().build()) {
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("first", b -> b.balanced())));

                assertThat(runtime.config().bulkheads()
                        .map(BulkheadSnapshot::name)
                        .toList())
                        .containsExactly("first");
            }
        }
    }

    @Nested
    @DisplayName("mixed updates")
    class MixedUpdates {

        @Test
        void should_apply_both_patch_and_add_in_one_update_call() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(99))
                        .bulkhead("payments", b -> b.protective())));

                assertThat(report.componentOutcomes())
                        .containsEntry("inventory", ApplyOutcome.PATCHED)
                        .containsEntry("payments", ApplyOutcome.ADDED);
                assertThat(runtime.imperative().bulkhead("inventory").snapshot()
                        .maxConcurrentCalls()).isEqualTo(99);
                assertThat(runtime.imperative().findBulkhead("payments")).isPresent();
            }
        }

        @Test
        void should_leave_untouched_bulkheads_alone() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im
                            .bulkhead("a", b -> b.balanced())
                            .bulkhead("b", b -> b.protective())
                            .bulkhead("c", b -> b.permissive()))
                    .build()) {

                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("b", b -> b.maxConcurrentCalls(7))));

                assertThat(runtime.imperative().bulkhead("a").snapshot().maxConcurrentCalls())
                        .as("a is untouched and keeps its balanced baseline").isEqualTo(50);
                assertThat(runtime.imperative().bulkhead("b").snapshot().maxConcurrentCalls())
                        .as("b receives the patch").isEqualTo(7);
                assertThat(runtime.imperative().bulkhead("c").snapshot().maxConcurrentCalls())
                        .as("c is untouched and keeps its permissive baseline").isEqualTo(200);
            }
        }
    }

    @Nested
    @DisplayName("closed runtime")
    class ClosedRuntime {

        @Test
        void should_throw_when_update_is_called_after_close() {
            InqRuntime runtime = Inqudium.configure().build();
            runtime.close();

            assertThatThrownBy(() -> runtime.update(u -> {
            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        }
    }
}
