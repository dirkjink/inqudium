package eu.inqudium.imperative.runtime;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.event.RuntimeComponentAddedEvent;
import eu.inqudium.config.event.RuntimeComponentPatchedEvent;
import eu.inqudium.config.event.RuntimeComponentRemovedEvent;
import eu.inqudium.config.event.RuntimeComponentVetoedEvent;
import eu.inqudium.config.lifecycle.ChangeDecision;
import eu.inqudium.config.lifecycle.ChangeRequest;
import eu.inqudium.config.lifecycle.ChangeRequestListener;
import eu.inqudium.config.lifecycle.ComponentField;
import eu.inqudium.config.runtime.ImperativeBulkhead;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.snapshot.BulkheadField;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.validation.VetoFinding;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEvent;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pin for the runtime-scoped topology events emitted by {@code runtime.update(...)}
 * (ADR-026, ADR-028). Each test subscribes on the {@code InqRuntime}-scoped publisher,
 * triggers an update, and verifies the payload — distinct from the {@code BuildReport}, which is
 * its own observation channel.
 */
@DisplayName("runtime-scoped topology events")
class RuntimeTopologyEventsTest {

    private static final InternalExecutor<String, String> IDENTITY =
            (chainId, callId, argument) -> argument;

    @Nested
    @DisplayName("RuntimeComponentAddedEvent")
    class AddedEvent {

        @Test
        void should_publish_when_a_new_bulkhead_is_added_via_update() {
            // What is to be tested: that adding a previously-unknown bulkhead via runtime.update
            // emits a RuntimeComponentAddedEvent on the runtime-scoped publisher.
            // Why successful: the captured event carries the new component's name, BULKHEAD type
            // and a non-null timestamp.
            // Why important: ADR-026 names this as the first-class topology signal that a new
            // component is now part of the runtime; tooling that watches "what is live" relies on
            // it instead of polling bulkheadNames().

            try (InqRuntime runtime = Inqudium.configure().build()) {
                List<RuntimeComponentAddedEvent> received = new ArrayList<>();
                runtime.general().eventPublisher()
                        .onEvent(RuntimeComponentAddedEvent.class, received::add);

                // When
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.balanced())));

                // Then
                assertThat(received).hasSize(1);
                RuntimeComponentAddedEvent event = received.get(0);
                assertThat(event.getElementName()).isEqualTo("inventory");
                assertThat(event.getElementType()).isEqualTo(InqElementType.BULKHEAD);
                assertThat(event.getTimestamp()).isNotNull();
            }
        }

        @Test
        void should_not_publish_when_a_patch_targets_an_existing_bulkhead() {
            // What is to be tested: that patching an existing bulkhead emits PatchedEvent, not
            // AddedEvent. The two are mutually exclusive per name within an update — only the
            // materialization branch emits Added.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                List<RuntimeComponentAddedEvent> received = new ArrayList<>();
                runtime.general().eventPublisher()
                        .onEvent(RuntimeComponentAddedEvent.class, received::add);

                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(99))));

                assertThat(received).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("RuntimeComponentPatchedEvent")
    class PatchedEvent {

        @Test
        void should_publish_with_touched_fields_when_patch_changes_a_field() {
            // What is to be tested: a successful patch produces a PatchedEvent with the patch's
            // touched-field set, so a subscriber can scope its reaction without inspecting the
            // BuildReport or diffing snapshots.
            // Why important: ADR-026 specifies touchedFields as the cheap way for tooling to know
            // "what changed" — a missing or empty set would force every consumer back to the
            // BuildReport.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.balanced().maxConcurrentCalls(15)))
                    .build()) {

                List<RuntimeComponentPatchedEvent> received = new ArrayList<>();
                runtime.general().eventPublisher()
                        .onEvent(RuntimeComponentPatchedEvent.class, received::add);

                // When
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(40))));

                // Then
                assertThat(received).hasSize(1);
                RuntimeComponentPatchedEvent event = received.get(0);
                assertThat(event.getElementName()).isEqualTo("inventory");
                assertThat(event.getElementType()).isEqualTo(InqElementType.BULKHEAD);
                assertThat(event.touchedFields())
                        .as("touchedFields exposes the patch's scope")
                        .extracting(ComponentField::name)
                        .contains(BulkheadField.MAX_CONCURRENT_CALLS.name());
                assertThat(event.getTimestamp()).isNotNull();
            }
        }

        @Test
        void should_not_publish_when_outcome_is_UNCHANGED() {
            // What is to be tested: that a patch whose touched fields all already match the
            // current value resolves to UNCHANGED and does NOT emit a PatchedEvent.
            // Why successful: the recorded list stays empty after the no-op update.
            // Why important: ADR-026 design choice — only "real" changes are signalled, mirroring
            // RuntimeComponentRemovedEvent's "real removal only" rule. Otherwise, every
            // re-emission of the desired state by a format adapter would flood subscribers.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.balanced().maxConcurrentCalls(15)))
                    .build()) {

                List<RuntimeComponentPatchedEvent> received = new ArrayList<>();
                runtime.general().eventPublisher()
                        .onEvent(RuntimeComponentPatchedEvent.class, received::add);

                // When — repaint the same value
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(15))));

                // Then
                assertThat(received).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("RuntimeComponentVetoedEvent")
    class VetoedEvent {

        @Test
        void should_publish_when_a_listener_vetoes_a_hot_patch() {
            // What is to be tested: that a listener veto on a hot patch produces a VetoedEvent
            // carrying the same VetoFinding that lands in the BuildReport.
            // Why important: subscribers that drive alerts on policy rejections must be able to
            // see the reason and source without parsing every BuildReport.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory",
                            b -> b.balanced().maxConcurrentCalls(15)))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);
                bh.onChangeRequest(req -> ChangeDecision.veto("policy: limits frozen"));

                List<RuntimeComponentVetoedEvent> received = new ArrayList<>();
                runtime.general().eventPublisher()
                        .onEvent(RuntimeComponentVetoedEvent.class, received::add);

                // When
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(40))));

                // Then
                assertThat(received).hasSize(1);
                RuntimeComponentVetoedEvent event = received.get(0);
                assertThat(event.getElementName()).isEqualTo("inventory");
                assertThat(event.getElementType()).isEqualTo(InqElementType.BULKHEAD);
                VetoFinding finding = event.vetoFinding();
                assertThat(finding.reason()).isEqualTo("policy: limits frozen");
                assertThat(finding.source()).isEqualTo(VetoFinding.Source.LISTENER);
                assertThat(finding.touchedFields())
                        .extracting(ComponentField::name)
                        .contains(BulkheadField.MAX_CONCURRENT_CALLS.name());
            }
        }

        @Test
        void should_publish_when_a_listener_vetoes_a_hot_removal() {
            // What is to be tested: that a removal vetoed by decideRemoval also produces a
            // VetoedEvent. Both veto kinds — patch and removal — flow through the same event,
            // distinguished only by the finding's empty touchedFields set on removal.
            // Why important: ADR-028 unifies veto reporting; tooling should not need a second
            // event class for removals.

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
                        return ChangeDecision.veto("policy: do not remove during peak hours");
                    }
                });

                List<RuntimeComponentVetoedEvent> received = new ArrayList<>();
                runtime.general().eventPublisher()
                        .onEvent(RuntimeComponentVetoedEvent.class, received::add);

                // When
                runtime.update(u -> u.imperative(im -> im.removeBulkhead("inventory")));

                // Then
                assertThat(received).hasSize(1);
                RuntimeComponentVetoedEvent event = received.get(0);
                assertThat(event.getElementName()).isEqualTo("inventory");
                VetoFinding finding = event.vetoFinding();
                assertThat(finding.reason()).isEqualTo("policy: do not remove during peak hours");
                assertThat(finding.source()).isEqualTo(VetoFinding.Source.LISTENER);
                assertThat(finding.touchedFields())
                        .as("removal vetoes carry an empty touched-field set")
                        .isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("emission ordering within a single update")
    class EmissionOrdering {

        @Test
        void should_emit_patches_before_removals_for_a_combined_update() {
            // What is to be tested: when a single runtime.update produces both a patch on one
            // component and a removal on another, the runtime-scoped publisher receives the
            // events in processing order — patch first, then removal — matching the order
            // DefaultImperative.applyUpdate iterates over its sections.
            // Why successful: the collected event list shows PatchedEvent at index 0 and
            // RemovedEvent at index 1 with the correct names attached.
            // Why important: subscribers that build per-component state machines (e.g. "this
            // component went through three changes today") need a stable, observable order. A
            // future refactor that interleaved or reversed the iteration would invisibly break
            // those consumers — the test pins the contract.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im
                            .bulkhead("a", b -> b.balanced().maxConcurrentCalls(10))
                            .bulkhead("b", b -> b.balanced().maxConcurrentCalls(20)))
                    .build()) {

                List<InqEvent> received = new ArrayList<>();
                runtime.general().eventPublisher()
                        .onEvent(RuntimeComponentPatchedEvent.class, received::add);
                runtime.general().eventPublisher()
                        .onEvent(RuntimeComponentRemovedEvent.class, received::add);

                // When — patch on "a", removal on "b" in a single update.
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("a", b -> b.maxConcurrentCalls(99))
                        .removeBulkhead("b")));

                // Then
                assertThat(received).hasSize(2);
                assertThat(received.get(0))
                        .as("patch is emitted before removal")
                        .isInstanceOf(RuntimeComponentPatchedEvent.class);
                assertThat(received.get(0).getElementName()).isEqualTo("a");
                assertThat(received.get(1))
                        .isInstanceOf(RuntimeComponentRemovedEvent.class);
                assertThat(received.get(1).getElementName()).isEqualTo("b");
            }
        }

        @Test
        void should_emit_added_before_removed_for_a_combined_update() {
            // The patches-then-removals iteration applies to ADDED as well — a brand-new
            // bulkhead added in the same update as a removal of another should fire AddedEvent
            // before RemovedEvent.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("legacy", b -> b.balanced()))
                    .build()) {

                List<InqEvent> received = new ArrayList<>();
                runtime.general().eventPublisher()
                        .onEvent(RuntimeComponentAddedEvent.class, received::add);
                runtime.general().eventPublisher()
                        .onEvent(RuntimeComponentRemovedEvent.class, received::add);

                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("fresh", b -> b.protective())
                        .removeBulkhead("legacy")));

                assertThat(received).hasSize(2);
                assertThat(received.get(0))
                        .isInstanceOf(RuntimeComponentAddedEvent.class);
                assertThat(received.get(0).getElementName()).isEqualTo("fresh");
                assertThat(received.get(1))
                        .isInstanceOf(RuntimeComponentRemovedEvent.class);
                assertThat(received.get(1).getElementName()).isEqualTo("legacy");
            }
        }
    }
}
