package eu.inqudium.imperative.runtime;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.event.RuntimeComponentAddedEvent;
import eu.inqudium.config.event.RuntimeComponentPatchedEvent;
import eu.inqudium.config.event.RuntimeComponentRemovedEvent;
import eu.inqudium.config.event.RuntimeComponentVetoedEvent;
import eu.inqudium.config.lifecycle.ChangeDecision;
import eu.inqudium.config.lifecycle.ChangeRequest;
import eu.inqudium.config.lifecycle.ChangeRequestListener;
import eu.inqudium.config.runtime.ComponentKey;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.validation.ApplyOutcome;
import eu.inqudium.config.validation.BuildReport;
import eu.inqudium.config.validation.VetoFinding;
import eu.inqudium.core.event.InqEvent;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("runtime.dryRun")
class RuntimeDryRunTest {

    private static final InternalExecutor<String, String> IDENTITY =
            (chainId, callId, argument) -> argument;

    private static final ComponentKey INVENTORY_KEY =
            new ComponentKey("inventory", ImperativeTag.INSTANCE);

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        void should_report_PATCHED_without_mutating_the_live_snapshot() {
            // What is to be tested: dryRun against a valid patch reports the same outcome an
            // update would have produced (PATCHED) but leaves the live snapshot untouched.
            // Why successful: the post-dryRun snapshot still carries the original
            // maxConcurrentCalls value; a follow-up update against the same patch flips the
            // value, confirming nothing committed in between.
            // Why important: this is the headline contract of ADR-028's dryRun — CI/CD
            // pipelines validate planned changes against running systems before issuing the
            // real update, and any silent mutation would defeat the safety net.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.balanced().maxConcurrentCalls(15)))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");

                // When
                BuildReport report = runtime.dryRun(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(40))));

                // Then
                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.PATCHED);
                assertThat(report.vetoFindings()).isEmpty();
                assertThat(bh.snapshot().maxConcurrentCalls())
                        .as("live snapshot is untouched by dryRun")
                        .isEqualTo(15);

                // Sanity: the same patch applied via update flips the value.
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(40))));
                assertThat(bh.snapshot().maxConcurrentCalls()).isEqualTo(40);
            }
        }

        @Test
        void should_report_UNCHANGED_for_a_no_op_patch() {
            // A patch whose touched fields all already match the current snapshot resolves to
            // UNCHANGED on dryRun, mirroring update's behaviour.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.balanced().maxConcurrentCalls(15)))
                    .build()) {

                BuildReport report = runtime.dryRun(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(15))));

                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.UNCHANGED);
            }
        }
    }

    @Nested
    @DisplayName("ADDED — would-add does not register")
    class AddedNotRegistered {

        @Test
        void should_report_ADDED_but_not_actually_register_the_bulkhead() {
            // What is to be tested: dryRun for a previously-unknown name reports ADDED yet
            // findBulkhead and bulkheadNames do not see the prospective component afterwards.
            // Why important: materialization wires up a live container, an event publisher and
            // potentially listener subscriptions; a dryRun that leaked any of those would have
            // observable side effects on the runtime — exactly what dryRun is supposed to
            // forbid (REFACTORING.md 2.5).

            try (InqRuntime runtime = Inqudium.configure().build()) {

                BuildReport report = runtime.dryRun(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.balanced())));

                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.ADDED);
                assertThat(runtime.imperative().findBulkhead("inventory"))
                        .as("dryRun does not materialize a real bulkhead")
                        .isEmpty();
                assertThat(runtime.imperative().bulkheadNames()).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("REMOVED — would-remove leaves the component intact")
    class RemovedNotActuallyRemoved {

        @Test
        void should_report_REMOVED_but_keep_the_bulkhead_in_place() {
            // What is to be tested: dryRun on a removeBulkhead reports REMOVED, but the
            // bulkhead is still reachable through the runtime afterwards and its handle still
            // serves traffic. Confirms the dispatcher's removal-decision step is reused without
            // the container's tear-down.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);

                BuildReport report = runtime.dryRun(u -> u.imperative(im -> im
                        .removeBulkhead("inventory")));

                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.REMOVED);
                assertThat(runtime.imperative().findBulkhead("inventory")).isPresent();
                // Handle still works post-dryRun (would throw ComponentRemovedException after a
                // real removal).
                assertThat(bh.execute(2L, 2L, "still-here", IDENTITY))
                        .isEqualTo("still-here");
            }
        }

        @Test
        void should_report_UNCHANGED_for_removal_of_unknown_name() {
            // Same idempotence rule as update: removing a name that does not exist is a no-op,
            // not an error.

            try (InqRuntime runtime = Inqudium.configure().build()) {

                BuildReport report = runtime.dryRun(u -> u.imperative(im -> im
                        .removeBulkhead("does-not-exist")));

                assertThat(report.componentOutcomes())
                        .containsEntry(
                                new ComponentKey("does-not-exist", ImperativeTag.INSTANCE),
                                ApplyOutcome.UNCHANGED);
            }
        }
    }

    @Nested
    @DisplayName("veto chain")
    class VetoChain {

        @Test
        void should_report_VETOED_for_a_listener_vetoed_hot_patch() {
            // What is to be tested: dryRun against a hot patch declined by a registered listener
            // reports the same VETOED outcome and VetoFinding the matching update would have
            // produced. Confirms the decide-only path runs the full listener chain.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.balanced().maxConcurrentCalls(15)))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);
                bh.onChangeRequest(req -> ChangeDecision.veto("policy: limits frozen"));

                BuildReport report = runtime.dryRun(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(40))));

                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.VETOED);
                assertThat(report.vetoFindings()).hasSize(1);
                VetoFinding finding = report.vetoFindings().get(0);
                assertThat(finding.componentKey()).isEqualTo(INVENTORY_KEY);
                assertThat(finding.reason()).isEqualTo("policy: limits frozen");
                assertThat(finding.source()).isEqualTo(VetoFinding.Source.LISTENER);
                assertThat(bh.snapshot().maxConcurrentCalls())
                        .as("vetoed dryRun leaves the snapshot at its original value")
                        .isEqualTo(15);
            }
        }

        @Test
        void should_report_VETOED_for_a_listener_vetoed_hot_removal() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);
                bh.onChangeRequest(new ChangeRequestListener<BulkheadSnapshot>() {
                    @Override
                    public ChangeDecision decide(ChangeRequest<BulkheadSnapshot> request) {
                        return ChangeDecision.accept();
                    }

                    @Override
                    public ChangeDecision decideRemoval(BulkheadSnapshot currentSnapshot) {
                        return ChangeDecision.veto("policy: do not remove during peak");
                    }
                });

                BuildReport report = runtime.dryRun(u -> u.imperative(im -> im
                        .removeBulkhead("inventory")));

                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.VETOED);
                assertThat(report.vetoFindings()).hasSize(1);
                assertThat(report.vetoFindings().get(0).reason())
                        .isEqualTo("policy: do not remove during peak");
                assertThat(runtime.imperative().findBulkhead("inventory"))
                        .as("vetoed dryRun on removal leaves the component in place")
                        .isPresent();
            }
        }
    }

    @Nested
    @DisplayName("no topology events")
    class NoTopologyEvents {

        @Test
        void should_not_publish_any_topology_event_for_a_combined_dry_run() {
            // What is to be tested: across every shape of dryRun outcome — ADDED on a new name,
            // PATCHED on an existing name, REMOVED on another, VETOED on a fourth — none of the
            // four runtime topology events fires on the runtime publisher.
            // Why successful: the captured event list is empty after dryRun.
            // Why important: ADR-026 / 028 designate dryRun as observation-only. A subscriber
            // that reacts to topology events would otherwise be tripped by every CI/CD
            // validation, breaking the "validate before commit" workflow this method exists for.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im
                            .bulkhead("alpha", b -> b.balanced())
                            .bulkhead("beta", b -> b.balanced())
                            .bulkhead("gamma", b -> b.balanced()))
                    .build()) {

                // Warm gamma so a hot-patch listener veto can engage.
                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> gamma =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("gamma");
                gamma.execute(1L, 1L, "warm", IDENTITY);
                gamma.onChangeRequest(req -> ChangeDecision.veto("frozen"));

                List<InqEvent> received = new ArrayList<>();
                runtime.general().eventPublisher()
                        .onEvent(RuntimeComponentAddedEvent.class, received::add);
                runtime.general().eventPublisher()
                        .onEvent(RuntimeComponentPatchedEvent.class, received::add);
                runtime.general().eventPublisher()
                        .onEvent(RuntimeComponentRemovedEvent.class, received::add);
                runtime.general().eventPublisher()
                        .onEvent(RuntimeComponentVetoedEvent.class, received::add);

                // When — every shape of outcome in a single dryRun.
                runtime.dryRun(u -> u.imperative(im -> im
                        .bulkhead("delta", b -> b.protective())                // ADDED
                        .bulkhead("alpha", b -> b.maxConcurrentCalls(99))      // PATCHED
                        .removeBulkhead("beta")                                 // REMOVED
                        .bulkhead("gamma", b -> b.maxConcurrentCalls(99))));   // VETOED

                // Then
                assertThat(received).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("idempotence")
    class Idempotence {

        @Test
        void two_consecutive_dry_runs_should_produce_equivalent_outcomes() {
            // What is to be tested: calling dryRun twice with the same updater on an unchanged
            // runtime produces identical componentOutcomes and vetoFindings. This is the
            // "no hidden side effects" pin — if dryRun mutated anything between calls, the
            // second call would reflect that drift.
            // Why important: CI/CD pipelines may dryRun the same plan multiple times (for
            // example as part of a retry loop or a pre-commit hook); the contract has to be
            // that the verdict is stable as long as the runtime is.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im
                            .bulkhead("inventory", b -> b.balanced().maxConcurrentCalls(15)))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);

                BuildReport first = runtime.dryRun(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(40))
                        .bulkhead("payments", b -> b.protective())));

                BuildReport second = runtime.dryRun(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(40))
                        .bulkhead("payments", b -> b.protective())));

                assertThat(second.componentOutcomes())
                        .as("componentOutcomes match across consecutive dryRuns")
                        .isEqualTo(first.componentOutcomes());
                assertThat(second.vetoFindings())
                        .as("vetoFindings match across consecutive dryRuns")
                        .isEqualTo(first.vetoFindings());
                assertThat(runtime.imperative().findBulkhead("payments"))
                        .as("payments was never actually added")
                        .isEmpty();
                assertThat(bh.snapshot().maxConcurrentCalls())
                        .as("inventory was never actually patched")
                        .isEqualTo(15);
            }
        }
    }

    @Nested
    @DisplayName("multi-component atomicity")
    class MultiComponent {

        @Test
        void should_produce_one_outcome_per_component_in_a_combined_update() {
            // Per-component atomicity carries across to dryRun: a single dryRun that patches one
            // bulkhead, adds another, and removes a third produces one outcome per component
            // and the runtime stays unchanged.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im
                            .bulkhead("alpha", b -> b.balanced().maxConcurrentCalls(10))
                            .bulkhead("beta", b -> b.balanced()))
                    .build()) {

                BuildReport report = runtime.dryRun(u -> u.imperative(im -> im
                        .bulkhead("alpha", b -> b.maxConcurrentCalls(99))
                        .bulkhead("gamma", b -> b.protective())
                        .removeBulkhead("beta")));

                assertThat(report.componentOutcomes())
                        .containsEntry(
                                new ComponentKey("alpha", ImperativeTag.INSTANCE),
                                ApplyOutcome.PATCHED)
                        .containsEntry(
                                new ComponentKey("gamma", ImperativeTag.INSTANCE),
                                ApplyOutcome.ADDED)
                        .containsEntry(
                                new ComponentKey("beta", ImperativeTag.INSTANCE),
                                ApplyOutcome.REMOVED);
                assertThat(runtime.imperative().bulkheadNames())
                        .as("runtime is unchanged after the dryRun")
                        .containsExactlyInAnyOrder("alpha", "beta");
                assertThat(runtime.imperative().bulkhead("alpha").snapshot()
                        .maxConcurrentCalls())
                        .isEqualTo(10);
            }
        }
    }
}
