package eu.inqudium.imperative.runtime;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.lifecycle.ChangeDecision;
import eu.inqudium.config.lifecycle.LifecycleState;
import eu.inqudium.config.runtime.ComponentKey;
import eu.inqudium.config.runtime.ImperativeBulkhead;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.validation.ApplyOutcome;
import eu.inqudium.config.validation.BuildReport;
import eu.inqudium.core.pipeline.InternalExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

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
                        .containsEntry(
                                new ComponentKey("inventory", ImperativeTag.INSTANCE),
                                ApplyOutcome.PATCHED);
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
        void preset_only_update_should_leave_events_untouched() {
            // What is to be tested: that an update which only calls a preset does NOT silently
            // overwrite the live snapshot's events configuration. The patch must not touch
            // EVENTS unless the user explicitly calls events(...); untouched fields inherit
            // from the current snapshot.
            // Why successful: after the preset-only update, bulkhead.snapshot().events()
            // returns the configuration that was set at initial build (allEnabled), not the
            // disabled() default the builder constructor would have applied if EVENTS were
            // touched implicitly.
            // Why important: this is the exact bug that motivated removing the constructor's
            // defaulting touch on EVENTS. A regression would silently disable per-call event
            // observability whenever any preset-only update happened — the kind of bug that is
            // both critical (events stop flowing) and easy to miss without a pinning test.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b
                            .balanced()
                            .events(BulkheadEventConfig.allEnabled())))
                    .build()) {

                // Sanity-check the initial state.
                assertThat(runtime.imperative().bulkhead("inventory").snapshot().events())
                        .as("initial snapshot carries the events configuration the user set")
                        .isEqualTo(BulkheadEventConfig.allEnabled());

                // When — preset-only update that does not call events(...)
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.permissive())));

                // Then — events stays at allEnabled, inherited from the live snapshot.
                BulkheadSnapshot after =
                        runtime.imperative().bulkhead("inventory").snapshot();
                assertThat(after.events())
                        .as("preset-only update inherits events from the live snapshot")
                        .isEqualTo(BulkheadEventConfig.allEnabled());
                assertThat(after.maxConcurrentCalls())
                        .as("the preset's own fields are still applied")
                        .isEqualTo(200);
                assertThat(after.derivedFromPreset()).isEqualTo("permissive");
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
                        .containsEntry(
                                new ComponentKey("inventory", ImperativeTag.INSTANCE),
                                ApplyOutcome.UNCHANGED);
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
                        .containsEntry(
                                new ComponentKey("payments", ImperativeTag.INSTANCE),
                                ApplyOutcome.ADDED);
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
                        .containsEntry(
                                new ComponentKey("inventory", ImperativeTag.INSTANCE),
                                ApplyOutcome.PATCHED)
                        .containsEntry(
                                new ComponentKey("payments", ImperativeTag.INSTANCE),
                                ApplyOutcome.ADDED);
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
    @DisplayName("dispatcher routing")
    class DispatcherRouting {

        @Test
        void cold_component_patch_should_apply_even_when_a_veto_listener_is_registered() {
            // What is to be tested: that registering a {@link
            // eu.inqudium.config.lifecycle.ChangeRequestListener ChangeRequestListener} on a cold
            // bulkhead does not block subsequent updates. The dispatcher (UpdateDispatcher) must
            // bypass the listener chain whenever the component is COLD, even if a listener that
            // would veto every patch is already registered.
            // Why successful: the bulkhead's snapshot reflects the new value after update, the
            // outcome is PATCHED, and the listener never had a chance to veto — proven by the
            // listener's invocation counter staying at zero.
            // Why important: ADR-028 promises that early-phase configuration changes (Spring
            // Boot bootstrap, post-construct customization, format-adapter reloads before any
            // call has been served) are unburdened by veto machinery. A regression that wired
            // listeners into the cold path would silently break startup-time configuration in
            // every framework integration that relies on it.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.balanced().maxConcurrentCalls(15)))
                    .build()) {

                ImperativeBulkhead bh = runtime.imperative().bulkhead("inventory");
                assertThat(bh.lifecycleState())
                        .as("the bulkhead is cold before the first execute")
                        .isEqualTo(LifecycleState.COLD);

                AtomicInteger listenerCalls = new AtomicInteger();
                bh.onChangeRequest(req -> {
                    listenerCalls.incrementAndGet();
                    return ChangeDecision.veto("would always veto");
                });

                // When
                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(40))));

                // Then
                assertThat(bh.snapshot().maxConcurrentCalls()).isEqualTo(40);
                assertThat(report.componentOutcomes())
                        .containsEntry(
                                new ComponentKey("inventory", ImperativeTag.INSTANCE),
                                ApplyOutcome.PATCHED);
                assertThat(report.vetoFindings())
                        .as("cold path bypasses the listener chain — no findings emitted")
                        .isEmpty();
                assertThat(listenerCalls.get())
                        .as("the listener must not be consulted on the cold path")
                        .isZero();
            }
        }

        @Test
        void hot_component_update_should_apply_when_no_listener_is_registered() {
            // What is to be tested: that a HOT bulkhead with no registered listeners still
            // accepts patches end-to-end through runtime.update. Why: an opt-in veto API must
            // not impose a default policy on components that do not subscribe.
            // Why important: this is the operational default — most production components run
            // hot without a listener; the absence of one must be a clean accept.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.balanced().maxConcurrentCalls(15)))
                    .build()) {

                ImperativeBulkhead bh = runtime.imperative().bulkhead("inventory");
                InternalExecutor<String, String> identity =
                        (chainId, callId, argument) -> argument;
                bh.execute(1L, 1L, "warm", identity);
                assertThat(bh.lifecycleState()).isEqualTo(LifecycleState.HOT);

                // When
                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(99))));

                // Then
                assertThat(report.componentOutcomes())
                        .containsEntry(
                                new ComponentKey("inventory", ImperativeTag.INSTANCE),
                                ApplyOutcome.PATCHED);
                assertThat(report.vetoFindings()).isEmpty();
                assertThat(bh.snapshot().maxConcurrentCalls()).isEqualTo(99);
            }
        }

        @Test
        void multi_component_update_should_apply_per_component_atomicity_under_a_partial_veto() {
            // What is to be tested: ADR-028 per-component patch atomicity in a multi-component
            // update — a veto on component A must not block the patch to component B, both
            // outcomes appear in the report, and the veto findings list contains exactly one
            // entry (for A only).
            // Why successful: A's snapshot remains at its pre-update value while B's reflects
            // the new value; report.componentOutcomes maps A to VETOED and B to PATCHED;
            // report.vetoFindings has one finding whose componentKey identifies A.
            // Why important: if a veto on A leaked into B's processing — for example via a
            // shared exception path or a bailing loop — partial updates would silently fail and
            // operators would see neither the apply on B nor the veto on A.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im
                            .bulkhead("a", b -> b.balanced().maxConcurrentCalls(15))
                            .bulkhead("b", b -> b.balanced().maxConcurrentCalls(20)))
                    .build()) {

                ImperativeBulkhead bhA = runtime.imperative().bulkhead("a");
                ImperativeBulkhead bhB = runtime.imperative().bulkhead("b");
                InternalExecutor<String, String> identity =
                        (chainId, callId, argument) -> argument;
                bhA.execute(1L, 1L, "warm", identity);
                bhB.execute(1L, 1L, "warm", identity);
                assertThat(bhA.lifecycleState()).isEqualTo(LifecycleState.HOT);
                assertThat(bhB.lifecycleState()).isEqualTo(LifecycleState.HOT);

                bhA.onChangeRequest(req -> ChangeDecision.veto("A is frozen"));
                // No listener on B — its patch flows through.

                // When
                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("a", b -> b.maxConcurrentCalls(99))
                        .bulkhead("b", b -> b.maxConcurrentCalls(77))));

                // Then
                ComponentKey keyA = new ComponentKey("a", ImperativeTag.INSTANCE);
                ComponentKey keyB = new ComponentKey("b", ImperativeTag.INSTANCE);
                assertThat(report.componentOutcomes())
                        .containsEntry(keyA, ApplyOutcome.VETOED)
                        .containsEntry(keyB, ApplyOutcome.PATCHED);
                assertThat(report.vetoFindings()).hasSize(1);
                assertThat(report.vetoFindings().get(0).componentKey()).isEqualTo(keyA);
                assertThat(report.vetoFindings().get(0).reason()).isEqualTo("A is frozen");
                assertThat(bhA.snapshot().maxConcurrentCalls())
                        .as("A is vetoed and keeps its prior value")
                        .isEqualTo(15);
                assertThat(bhB.snapshot().maxConcurrentCalls())
                        .as("B is patched independently of A's veto")
                        .isEqualTo(77);
            }
        }

        @Test
        void hot_component_update_should_be_vetoed_when_a_listener_returns_a_veto() {
            // What is to be tested: end-to-end veto propagation. A listener registered on a HOT
            // bulkhead returns a Veto; the runtime.update call returns a BuildReport with a
            // VETOED outcome and a populated vetoFindings list, and the bulkhead's snapshot
            // remains untouched.
            // Why successful: the BuildReport carries both the per-component VETOED outcome and
            // the VetoFinding (with the listener's reason and Source.LISTENER); the bulkhead's
            // snapshot stays at its pre-update value.
            // Why important: this is the headline contract of ADR-028's veto chain. A
            // regression at the propagation layer (dispatcher → DefaultImperative →
            // DefaultInqRuntime → BuildReport) would silently drop findings and mislead
            // operators reading the report.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.balanced().maxConcurrentCalls(15)))
                    .build()) {

                ImperativeBulkhead bh = runtime.imperative().bulkhead("inventory");
                InternalExecutor<String, String> identity =
                        (chainId, callId, argument) -> argument;
                bh.execute(1L, 1L, "warm", identity);
                assertThat(bh.lifecycleState()).isEqualTo(LifecycleState.HOT);

                bh.onChangeRequest(req -> ChangeDecision.veto("policy disallows during business hours"));

                // When
                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(99))));

                // Then
                ComponentKey key = new ComponentKey("inventory", ImperativeTag.INSTANCE);
                assertThat(report.componentOutcomes())
                        .containsEntry(key, ApplyOutcome.VETOED);
                assertThat(report.vetoFindings()).hasSize(1);
                eu.inqudium.config.validation.VetoFinding finding = report.vetoFindings().get(0);
                assertThat(finding.componentKey()).isEqualTo(key);
                assertThat(finding.reason()).isEqualTo("policy disallows during business hours");
                assertThat(finding.source())
                        .isEqualTo(eu.inqudium.config.validation.VetoFinding.Source.LISTENER);
                assertThat(report.isSuccess())
                        .as("a vetoed patch is a policy outcome, not a validation failure")
                        .isTrue();
                assertThat(bh.snapshot().maxConcurrentCalls())
                        .as("vetoed patch must not be applied")
                        .isEqualTo(15);
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
