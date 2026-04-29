package eu.inqudium.imperative.runtime;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.event.RuntimeComponentRemovedEvent;
import eu.inqudium.config.lifecycle.ChangeDecision;
import eu.inqudium.config.lifecycle.ChangeRequest;
import eu.inqudium.config.lifecycle.ChangeRequestListener;
import eu.inqudium.config.runtime.ComponentKey;
import eu.inqudium.config.runtime.ComponentRemovedException;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.validation.ApplyOutcome;
import eu.inqudium.config.validation.BuildReport;
import eu.inqudium.config.validation.VetoFinding;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("structural removal via runtime.update")
class BulkheadRemovalTest {

    private static final InternalExecutor<String, String> IDENTITY =
            (chainId, callId, argument) -> argument;

    private static final ComponentKey INVENTORY_KEY =
            new ComponentKey("inventory", ImperativeTag.INSTANCE);

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        void should_remove_a_hot_bulkhead_and_drop_it_from_the_runtime() {
            // What is to be tested: end-to-end removal — the bulkhead disappears from
            // findBulkhead, the report carries REMOVED, and bulkhead(name) raises the same
            // IllegalArgumentException as for any unknown name.
            // Why important: this is the headline contract of ADR-026; a regression in any
            // intermediate layer (DSL → patches → dispatcher → container → map mutation) would
            // leave the runtime in an inconsistent state.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);

                // When
                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .removeBulkhead("inventory")));

                // Then
                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.REMOVED);
                assertThat(report.vetoFindings()).isEmpty();
                assertThat(runtime.imperative().findBulkhead("inventory"))
                        .as("findBulkhead returns Optional.empty after removal")
                        .isEqualTo(Optional.empty());
                assertThat(runtime.imperative().bulkheadNames()).doesNotContain("inventory");
                assertThatThrownBy(() -> runtime.imperative().bulkhead("inventory"))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        void should_remove_a_cold_bulkhead_without_warming() {
            // A cold component takes the dispatcher's cold-path bypass — no listeners, no
            // internal check, just drop. Pinning this so 2.10's strategy-hot-swap does not
            // accidentally regress the cold-path simplicity.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .removeBulkhead("inventory")));

                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.REMOVED);
                assertThat(runtime.imperative().findBulkhead("inventory")).isEmpty();
            }
        }

        @Test
        void should_publish_RuntimeComponentRemovedEvent_on_the_runtime_publisher() {
            // What is to be tested: that the runtime-scoped publisher carries the topology
            // event for a successful removal. Why: ADR-026 specifies this event so operational
            // tooling can dashboard "components removed" without parsing every BuildReport.
            // Why important: the publisher and the BuildReport are independent observation
            // channels; a regression that wired only one would leave half the consumers blind.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                List<RuntimeComponentRemovedEvent> received = new ArrayList<>();
                runtime.general().eventPublisher()
                        .onEvent(RuntimeComponentRemovedEvent.class, received::add);

                runtime.update(u -> u.imperative(im -> im.removeBulkhead("inventory")));

                assertThat(received).hasSize(1);
                RuntimeComponentRemovedEvent event = received.get(0);
                assertThat(event.getElementName()).isEqualTo("inventory");
                assertThat(event.getElementType()).isEqualTo(InqElementType.BULKHEAD);
                assertThat(event.getTimestamp()).isNotNull();
            }
        }

        @Test
        void should_report_UNCHANGED_when_removing_an_unknown_name() {
            // What is to be tested: that removing a name that is not present produces UNCHANGED
            // (per the work-item recommendation), not an error or a missing entry.
            // Why important: format adapters re-emit the desired runtime state; idempotent
            // removals (the name is gone in the desired state, also already gone in the
            // current state) must be a clean no-op rather than a warning.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .removeBulkhead("does-not-exist")));

                assertThat(report.componentOutcomes())
                        .containsEntry(
                                new ComponentKey("does-not-exist", ImperativeTag.INSTANCE),
                                ApplyOutcome.UNCHANGED);
                assertThat(report.vetoFindings()).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("inert handle after removal")
    class InertHandleAfterRemoval {

        @Test
        void execute_should_throw_ComponentRemovedException() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);
                runtime.update(u -> u.imperative(im -> im.removeBulkhead("inventory")));

                assertThatThrownBy(() -> bh.execute(2L, 2L, "after-removal", IDENTITY))
                        .isInstanceOf(ComponentRemovedException.class)
                        .hasMessageContaining("inventory");
            }
        }

        @Test
        void snapshot_should_throw_ComponentRemovedException() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                runtime.update(u -> u.imperative(im -> im.removeBulkhead("inventory")));

                assertThatThrownBy(bh::snapshot)
                        .isInstanceOf(ComponentRemovedException.class)
                        .hasMessageContaining("inventory");
            }
        }

        @Test
        void availablePermits_should_throw_ComponentRemovedException() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                runtime.update(u -> u.imperative(im -> im.removeBulkhead("inventory")));

                assertThatThrownBy(bh::availablePermits)
                        .isInstanceOf(ComponentRemovedException.class);
            }
        }

        @Test
        void concurrentCalls_should_throw_ComponentRemovedException() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                runtime.update(u -> u.imperative(im -> im.removeBulkhead("inventory")));

                assertThatThrownBy(bh::concurrentCalls)
                        .isInstanceOf(ComponentRemovedException.class);
            }
        }

        @Test
        void lifecycleState_should_throw_ComponentRemovedException() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                runtime.update(u -> u.imperative(im -> im.removeBulkhead("inventory")));

                assertThatThrownBy(bh::lifecycleState)
                        .isInstanceOf(ComponentRemovedException.class);
            }
        }

        @Test
        void exception_should_carry_the_components_identity() {
            // What is to be tested: that the ComponentRemovedException's identity fields match
            // the removed component's name and element type. Why: operators reading a stack
            // trace need to identify the component without trawling the message.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                runtime.update(u -> u.imperative(im -> im.removeBulkhead("inventory")));

                ComponentRemovedException ex =
                        org.junit.jupiter.api.Assertions.assertThrows(
                                ComponentRemovedException.class, bh::snapshot);
                assertThat(ex.componentName()).isEqualTo("inventory");
                assertThat(ex.elementType()).isEqualTo(InqElementType.BULKHEAD);
            }
        }
    }

    @Nested
    @DisplayName("veto chain on removal")
    class VetoChainOnRemoval {

        @Test
        void listener_decideRemoval_veto_should_block_removal_and_keep_component() {
            // What is to be tested: end-to-end veto of a removal — Component stays, snapshot
            // still readable, permits still queryable, vetoFinding carries the listener's
            // reason and Source.LISTENER.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.balanced().maxConcurrentCalls(15)))
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
                        return ChangeDecision.veto("policy: do not remove during peak hours");
                    }
                });

                // When
                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .removeBulkhead("inventory")));

                // Then
                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.VETOED);
                assertThat(report.vetoFindings()).hasSize(1);
                VetoFinding finding = report.vetoFindings().get(0);
                assertThat(finding.componentKey()).isEqualTo(INVENTORY_KEY);
                assertThat(finding.reason()).isEqualTo("policy: do not remove during peak hours");
                assertThat(finding.source()).isEqualTo(VetoFinding.Source.LISTENER);
                assertThat(runtime.imperative().findBulkhead("inventory"))
                        .as("vetoed removal leaves the component in place")
                        .isPresent();
                assertThat(bh.snapshot().maxConcurrentCalls())
                        .as("snapshot still readable after a vetoed removal")
                        .isEqualTo(15);
                assertThat(bh.availablePermits())
                        .as("permits still queryable")
                        .isPositive();
            }
        }

        @Test
        void patch_listener_should_not_block_removal_via_default_decideRemoval() {
            // What is to be tested: that a lambda listener written against patches (only
            // overrides decide) does NOT accidentally veto removals — the default
            // implementation of decideRemoval is accept. Why: ADR-028 design choice — patch
            // policies and removal policies are separate concerns, so default behaviour does
            // not leak across them.
            // Why important: every existing patch-policy listener registered before 2.3 keeps
            // working unchanged; nobody inadvertently locks themselves out of removals by
            // adding a patch policy.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);
                // SAM-form lambda — only overrides decide; decideRemoval inherits accept.
                bh.onChangeRequest(req -> ChangeDecision.veto("vetoes every patch"));

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .removeBulkhead("inventory")));

                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.REMOVED);
                assertThat(runtime.imperative().findBulkhead("inventory")).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("listener cleanup on removal")
    class ListenerCleanupOnRemoval {

        @Test
        void registration_handle_close_should_remain_safe_after_removal() {
            // What is to be tested: that the AutoCloseable returned by onChangeRequest can be
            // safely closed on an inert handle without throwing. Why: per ADR-028, listeners
            // on a removed component are silently retained on the inert handle; their
            // unregistration handles must therefore stay usable so client cleanup code (e.g. a
            // try-with-resources) does not crash on shutdown.
            // Why important: production code often pairs onChangeRequest with try-with-
            // resources or a shutdown hook; if close() threw on a removed component, every
            // graceful-shutdown path would leak exceptions.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                AutoCloseable handle = bh.onChangeRequest(req -> ChangeDecision.accept());
                runtime.update(u -> u.imperative(im -> im.removeBulkhead("inventory")));

                // When / Then — neither close throws.
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
                    handle.close();
                    handle.close();
                });
            }
        }
    }

    @Nested
    @DisplayName("multi-component atomicity on removal")
    class MultiComponentAtomicity {

        @Test
        void should_remove_one_and_patch_another_in_one_update_call() {
            // What is to be tested: that a removal on A and a patch on B both succeed in the
            // same update call, and both outcomes appear in the report. Why: ADR-026 per-
            // component atomicity extends to removals — they are just another kind of patch
            // routed through the same dispatcher.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im
                            .bulkhead("a", b -> b.balanced().maxConcurrentCalls(10))
                            .bulkhead("b", b -> b.balanced().maxConcurrentCalls(20)))
                    .build()) {

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .removeBulkhead("a")
                        .bulkhead("b", b -> b.maxConcurrentCalls(99))));

                assertThat(report.componentOutcomes())
                        .containsEntry(
                                new ComponentKey("a", ImperativeTag.INSTANCE),
                                ApplyOutcome.REMOVED)
                        .containsEntry(
                                new ComponentKey("b", ImperativeTag.INSTANCE),
                                ApplyOutcome.PATCHED);
                assertThat(runtime.imperative().findBulkhead("a")).isEmpty();
                assertThat(runtime.imperative().bulkhead("b").snapshot().maxConcurrentCalls())
                        .isEqualTo(99);
            }
        }

        @Test
        void should_keep_one_when_its_removal_is_vetoed_and_proceed_with_another() {
            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im
                            .bulkhead("a", b -> b.balanced())
                            .bulkhead("b", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> a =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("a");
                a.execute(1L, 1L, "warm", IDENTITY);
                a.onChangeRequest(new ChangeRequestListener<BulkheadSnapshot>() {
                    @Override
                    public ChangeDecision decide(ChangeRequest<BulkheadSnapshot> request) {
                        return ChangeDecision.accept();
                    }

                    @Override
                    public ChangeDecision decideRemoval(BulkheadSnapshot currentSnapshot) {
                        return ChangeDecision.veto("A is locked");
                    }
                });

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .removeBulkhead("a")
                        .removeBulkhead("b")));

                assertThat(report.componentOutcomes())
                        .containsEntry(
                                new ComponentKey("a", ImperativeTag.INSTANCE),
                                ApplyOutcome.VETOED)
                        .containsEntry(
                                new ComponentKey("b", ImperativeTag.INSTANCE),
                                ApplyOutcome.REMOVED);
                assertThat(report.vetoFindings()).hasSize(1);
                assertThat(runtime.imperative().findBulkhead("a")).isPresent();
                assertThat(runtime.imperative().findBulkhead("b")).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("DSL last-writer-wins")
    class DslLastWriterWins {

        @Test
        void removeBulkhead_after_bulkhead_in_same_traversal_should_remove() {
            // The DSL collapses to last-writer-wins per name within a single traversal.
            // bulkhead("x", ...) then removeBulkhead("x") leaves only the removal.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(99))
                        .removeBulkhead("inventory")));

                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.REMOVED);
                assertThat(runtime.imperative().findBulkhead("inventory")).isEmpty();
            }
        }

        @Test
        void bulkhead_after_removeBulkhead_in_same_traversal_should_patch() {
            // The reverse: removeBulkhead("x") then bulkhead("x", ...) leaves only the patch.
            // The runtime is patched (or the component re-added if absent), not removed.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.balanced().maxConcurrentCalls(15)))
                    .build()) {

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .removeBulkhead("inventory")
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(40))));

                assertThat(report.componentOutcomes())
                        .containsEntry(INVENTORY_KEY, ApplyOutcome.PATCHED);
                assertThat(runtime.imperative().findBulkhead("inventory")).isPresent();
                assertThat(runtime.imperative().bulkhead("inventory")
                        .snapshot().maxConcurrentCalls()).isEqualTo(40);
            }
        }
    }
}
