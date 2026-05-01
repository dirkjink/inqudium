package eu.inqudium.config.runtime;

import eu.inqudium.config.lifecycle.ChangeDecision;
import eu.inqudium.config.lifecycle.ChangeRequest;
import eu.inqudium.config.lifecycle.ChangeRequestListener;
import eu.inqudium.config.lifecycle.ComponentField;
import eu.inqudium.config.lifecycle.InternalMutabilityCheck;
import eu.inqudium.config.lifecycle.LifecycleAware;
import eu.inqudium.config.lifecycle.LifecycleState;
import eu.inqudium.config.lifecycle.ListenerRegistry;
import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.patch.BulkheadPatch;
import eu.inqudium.config.patch.ComponentPatch;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadField;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.config.validation.ApplyOutcome;
import eu.inqudium.config.validation.VetoFinding;
import eu.inqudium.core.log.LogAction;
import eu.inqudium.core.log.Logger;
import eu.inqudium.core.log.LoggerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("UpdateDispatcher")
class UpdateDispatcherTest {

    private static final ComponentKey KEY =
            new ComponentKey("inventory", ImperativeTag.INSTANCE);

    private static BulkheadSnapshot snapshot(int max) {
        return new BulkheadSnapshot(
                "inventory", max, Duration.ofMillis(100), Set.of(),
                null, BulkheadEventConfig.disabled(), new SemaphoreStrategyConfig());
    }

    /**
     * Test stand-in for a lifecycle-aware component handle. Implements
     * {@link LifecycleAware}, {@link ListenerRegistry}, and {@link InternalMutabilityCheck} so
     * the dispatcher's three-way intersection constraint is satisfied. Listeners are recorded
     * in registration order — the dispatcher iterates them by calling {@link #listeners()}.
     * The internal mutability check is configurable through {@link #setInternalCheck(InternalMutabilityCheck)}
     * and tracks the number of times it was consulted via {@link #internalCheckCalls()}.
     */
    private static final class FakeHandle
            implements LifecycleAware,
            ListenerRegistry<BulkheadSnapshot>,
            InternalMutabilityCheck<BulkheadSnapshot> {

        private final LifecycleState state;
        private final List<ChangeRequestListener<BulkheadSnapshot>> listeners = new ArrayList<>();
        private final AtomicInteger internalCheckCalls = new AtomicInteger();
        private InternalMutabilityCheck<BulkheadSnapshot> internalCheck = req -> ChangeDecision.accept();
        private java.util.function.Function<BulkheadSnapshot, ChangeDecision> removalCheck =
                snap -> ChangeDecision.accept();

        FakeHandle(LifecycleState state) {
            this.state = state;
        }

        void setInternalCheck(InternalMutabilityCheck<BulkheadSnapshot> check) {
            this.internalCheck = check;
        }

        void setRemovalCheck(
                java.util.function.Function<BulkheadSnapshot, ChangeDecision> check) {
            this.removalCheck = check;
        }

        int internalCheckCalls() {
            return internalCheckCalls.get();
        }

        @Override
        public LifecycleState lifecycleState() {
            return state;
        }

        @Override
        public AutoCloseable onChangeRequest(ChangeRequestListener<BulkheadSnapshot> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        @Override
        public List<ChangeRequestListener<BulkheadSnapshot>> listeners() {
            return List.copyOf(listeners);
        }

        @Override
        public ChangeDecision evaluate(ChangeRequest<BulkheadSnapshot> request) {
            internalCheckCalls.incrementAndGet();
            return internalCheck.evaluate(request);
        }

        @Override
        public ChangeDecision evaluateRemoval(BulkheadSnapshot currentSnapshot) {
            internalCheckCalls.incrementAndGet();
            return removalCheck.apply(currentSnapshot);
        }
    }

    @Nested
    @DisplayName("cold path")
    class ColdPath {

        @Test
        void should_apply_a_changing_patch_directly_and_return_PATCHED() {
            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            // When
            DispatchResult result = new UpdateDispatcher().dispatch(
                    KEY, new FakeHandle(LifecycleState.COLD), live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.PATCHED);
            assertThat(result.vetoFinding()).isEmpty();
            assertThat(live.snapshot().maxConcurrentCalls()).isEqualTo(25);
        }

        @Test
        void should_return_UNCHANGED_when_the_patch_produces_an_equal_snapshot() {
            // What is to be tested: that applying a patch which sets the same value as already
            // present returns UNCHANGED. Why: ADR-025/028 distinguish "applied without effect"
            // from "applied with effect" so dashboards do not report a no-op as a real change.
            // Why important: format adapters re-emit complete patches on every reload; without
            // UNCHANGED, every reload would look like a configuration churn event.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(10);

            // When
            DispatchResult result = new UpdateDispatcher().dispatch(
                    KEY, new FakeHandle(LifecycleState.COLD), live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.UNCHANGED);
            assertThat(result.vetoFinding()).isEmpty();
            assertThat(live.snapshot().maxConcurrentCalls()).isEqualTo(10);
        }

        @Test
        void should_return_UNCHANGED_for_an_empty_patch() {
            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();

            // When
            DispatchResult result = new UpdateDispatcher().dispatch(
                    KEY, new FakeHandle(LifecycleState.COLD), live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.UNCHANGED);
            assertThat(result.vetoFinding()).isEmpty();
        }

        @Test
        void should_not_consult_listeners_even_when_listeners_are_registered() {
            // What is to be tested: that the dispatcher's cold path bypasses the listener chain
            // entirely. Why: ADR-028 is explicit that cold updates are "free of veto machinery";
            // listeners on a cold component must not run.
            // Why important: format-adapter-driven configuration loads happen during cold-state
            // bootstrap; if listeners ran then, every framework startup would fire spurious
            // veto evaluations against an as-yet-unobserved snapshot stream.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            FakeHandle target = new FakeHandle(LifecycleState.COLD);
            AtomicInteger calls = new AtomicInteger();
            target.onChangeRequest(req -> {
                calls.incrementAndGet();
                return ChangeDecision.veto("would always veto");
            });

            // When
            DispatchResult result = new UpdateDispatcher().dispatch(KEY, target, live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.PATCHED);
            assertThat(calls.get())
                    .as("listener must not be consulted on the cold path")
                    .isZero();
            assertThat(live.snapshot().maxConcurrentCalls()).isEqualTo(25);
        }
    }

    @Nested
    @DisplayName("hot path — no listeners")
    class HotPathNoListeners {

        @Test
        void should_apply_the_patch_when_no_listener_is_registered() {
            // What is to be tested: that a HOT component without registered listeners still
            // applies the patch. Why: registering listeners is opt-in; the absence of listeners
            // must not block hot updates.
            // Why important: this is the default operational path for components in production —
            // most components run hot without anybody having registered a veto listener.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            // When
            DispatchResult result = new UpdateDispatcher().dispatch(
                    KEY, new FakeHandle(LifecycleState.HOT), live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.PATCHED);
            assertThat(result.vetoFinding()).isEmpty();
            assertThat(live.snapshot().maxConcurrentCalls()).isEqualTo(25);
        }

        @Test
        void should_return_UNCHANGED_for_a_no_op_patch_on_hot_component() {
            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(10);

            // When
            DispatchResult result = new UpdateDispatcher().dispatch(
                    KEY, new FakeHandle(LifecycleState.HOT), live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.UNCHANGED);
            assertThat(result.vetoFinding()).isEmpty();
        }
    }

    @Nested
    @DisplayName("hot path — listener accepts")
    class HotPathListenerAccepts {

        @Test
        void should_apply_the_patch_when_a_single_listener_accepts() {
            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            AtomicInteger calls = new AtomicInteger();
            target.onChangeRequest(req -> {
                calls.incrementAndGet();
                return ChangeDecision.accept();
            });

            // When
            DispatchResult result = new UpdateDispatcher().dispatch(KEY, target, live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.PATCHED);
            assertThat(result.vetoFinding()).isEmpty();
            assertThat(calls.get())
                    .as("the listener must be consulted exactly once")
                    .isEqualTo(1);
            assertThat(live.snapshot().maxConcurrentCalls()).isEqualTo(25);
        }

        @Test
        void should_apply_the_patch_when_two_listeners_both_accept() {
            // What is to be tested: that the chain consults every listener and applies the patch
            // when every one accepts. Why: ADR-028 specifies a conjunctive chain — accept means
            // "no objection from this listener", and only the unanimous case proceeds to apply.
            // Why important: registration order is preserved across listeners; a missed listener
            // would be a silent policy bypass.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            AtomicInteger first = new AtomicInteger();
            AtomicInteger second = new AtomicInteger();
            target.onChangeRequest(req -> {
                first.incrementAndGet();
                return ChangeDecision.accept();
            });
            target.onChangeRequest(req -> {
                second.incrementAndGet();
                return ChangeDecision.accept();
            });

            // When
            DispatchResult result = new UpdateDispatcher().dispatch(KEY, target, live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.PATCHED);
            assertThat(result.vetoFinding()).isEmpty();
            assertThat(first.get()).isEqualTo(1);
            assertThat(second.get()).isEqualTo(1);
            assertThat(live.snapshot().maxConcurrentCalls()).isEqualTo(25);
        }
    }

    @Nested
    @DisplayName("hot path — listener vetoes")
    class HotPathListenerVetoes {

        @Test
        void should_reject_the_patch_when_a_single_listener_vetoes() {
            // What is to be tested: that a Veto from a single registered listener rejects the
            // patch outright. Why: ADR-028's conjunctive chain — one veto suffices to reject.
            // Why successful: outcome=VETOED, vetoFinding present with the listener's reason and
            // Source.LISTENER, the live snapshot is unchanged from its pre-dispatch value.
            // Why important: per-component patch atomicity — a refused patch must leave the
            // prior snapshot intact so subsequent dashboards and observers see a consistent
            // state.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            target.onChangeRequest(req -> ChangeDecision.veto("policy disallows growth above 20"));

            // When
            DispatchResult result = new UpdateDispatcher().dispatch(KEY, target, live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.VETOED);
            assertThat(result.vetoFinding()).isPresent();
            VetoFinding finding = result.vetoFinding().orElseThrow();
            assertThat(finding.componentKey()).isEqualTo(KEY);
            assertThat(finding.reason()).isEqualTo("policy disallows growth above 20");
            assertThat(finding.source()).isEqualTo(VetoFinding.Source.LISTENER);
            assertThat(finding.touchedFields())
                    .extracting(ComponentField::name)
                    .containsExactly(BulkheadField.MAX_CONCURRENT_CALLS.name());
            assertThat(live.snapshot().maxConcurrentCalls())
                    .as("vetoed patch must not be applied")
                    .isEqualTo(10);
        }

        @Test
        void should_short_circuit_on_first_veto_and_skip_subsequent_listeners() {
            // What is to be tested: that the chain stops at the first Veto and does not consult
            // listeners registered after it. Why: ADR-028 makes this the conjunctive-chain
            // behaviour — once a veto has occurred, downstream listeners cannot reverse it, so
            // calling them would be wasted work and could mask the real veto reason.
            // Why important: spy-style listeners that expect to see every patch (audit trails)
            // must be aware that vetoed patches don't reach them; pinning the short-circuit
            // makes that contract explicit.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            AtomicInteger firstCalls = new AtomicInteger();
            AtomicInteger secondCalls = new AtomicInteger();
            target.onChangeRequest(req -> {
                firstCalls.incrementAndGet();
                return ChangeDecision.veto("first listener says no");
            });
            target.onChangeRequest(req -> {
                secondCalls.incrementAndGet();
                return ChangeDecision.accept();
            });

            // When
            DispatchResult result = new UpdateDispatcher().dispatch(KEY, target, live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.VETOED);
            assertThat(result.vetoFinding()).isPresent();
            assertThat(result.vetoFinding().orElseThrow().reason())
                    .isEqualTo("first listener says no");
            assertThat(firstCalls.get()).isEqualTo(1);
            assertThat(secondCalls.get())
                    .as("second listener must not be called once the first has vetoed")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("hot path — component-internal mutability check")
    class HotPathInternalCheck {

        @Test
        void should_apply_when_listener_accepts_and_internal_check_accepts() {
            // What is to be tested: that the dispatcher consults the internal check after the
            // listener chain accepts, and applies the patch when the check also accepts. Why:
            // ADR-028 step 8 places the internal check after the listeners as the last gate.
            // Why important: this is the happy path of the full ADR-028 sequence; if it were
            // mis-wired we would either skip the check or apply twice.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            AtomicInteger listenerCalls = new AtomicInteger();
            target.onChangeRequest(req -> {
                listenerCalls.incrementAndGet();
                return ChangeDecision.accept();
            });
            // FakeHandle's default internalCheck returns accept.

            // When
            DispatchResult result = new UpdateDispatcher().dispatch(KEY, target, live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.PATCHED);
            assertThat(result.vetoFinding()).isEmpty();
            assertThat(listenerCalls.get()).isEqualTo(1);
            assertThat(target.internalCheckCalls())
                    .as("internal check must run once after listener accept")
                    .isEqualTo(1);
            assertThat(live.snapshot().maxConcurrentCalls()).isEqualTo(25);
        }

        @Test
        void should_veto_when_listener_accepts_and_internal_check_vetoes() {
            // What is to be tested: that the internal check can reject a patch the listener
            // chain has already accepted. Why: the component is the last line of defence — a
            // permissive listener policy must not be able to drive the component into a state
            // its strategy cannot apply (ADR-028 defence-in-depth rationale).
            // Why successful: outcome is VETOED, the finding's source is COMPONENT_INTERNAL, and
            // the live snapshot is unchanged from its pre-dispatch value.
            // Why important: this is the only dispatcher path that produces a
            // COMPONENT_INTERNAL veto finding; a regression that called the check too late
            // (after apply) or not at all would silently lose its protection.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            target.onChangeRequest(req -> ChangeDecision.accept());
            target.setInternalCheck(req ->
                    ChangeDecision.veto("strategy cannot apply this in the current state"));

            // When
            DispatchResult result = new UpdateDispatcher().dispatch(KEY, target, live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.VETOED);
            assertThat(result.vetoFinding()).isPresent();
            VetoFinding finding = result.vetoFinding().orElseThrow();
            assertThat(finding.source()).isEqualTo(VetoFinding.Source.COMPONENT_INTERNAL);
            assertThat(finding.componentKey()).isEqualTo(KEY);
            assertThat(finding.reason())
                    .isEqualTo("strategy cannot apply this in the current state");
            assertThat(finding.touchedFields())
                    .extracting(ComponentField::name)
                    .containsExactly(BulkheadField.MAX_CONCURRENT_CALLS.name());
            assertThat(target.internalCheckCalls()).isEqualTo(1);
            assertThat(live.snapshot().maxConcurrentCalls())
                    .as("vetoed patch must not be applied")
                    .isEqualTo(10);
        }

        @Test
        void should_not_call_internal_check_when_a_listener_vetoes_first() {
            // What is to be tested: that a listener veto short-circuits the chain before the
            // internal check ever runs. Why: ADR-028 step 8 specifies the listener chain as the
            // first gate; running the internal check after a listener veto would be wasted work
            // and could produce confusing veto findings (the operator should see the listener
            // reason, not a never-relevant internal one).
            // Why successful: outcome is VETOED with Source.LISTENER, and the FakeHandle's
            // internalCheckCalls counter remains at zero.
            // Why important: spy-verifying the negative case pins the ordering contract; a
            // refactor that re-ordered the gates would silently flip every report's finding
            // source.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            target.onChangeRequest(req -> ChangeDecision.veto("listener says no"));
            target.setInternalCheck(req ->
                    ChangeDecision.veto("would also veto, but should not be asked"));

            // When
            DispatchResult result = new UpdateDispatcher().dispatch(KEY, target, live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.VETOED);
            assertThat(result.vetoFinding()).isPresent();
            assertThat(result.vetoFinding().orElseThrow().source())
                    .isEqualTo(VetoFinding.Source.LISTENER);
            assertThat(result.vetoFinding().orElseThrow().reason()).isEqualTo("listener says no");
            assertThat(target.internalCheckCalls())
                    .as("internal check must not be consulted once a listener has vetoed")
                    .isZero();
        }

        @Test
        void should_consult_internal_check_when_no_listener_is_registered() {
            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            target.setInternalCheck(req -> ChangeDecision.veto("internal-only veto"));

            // When
            DispatchResult result = new UpdateDispatcher().dispatch(KEY, target, live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.VETOED);
            assertThat(result.vetoFinding().orElseThrow().source())
                    .isEqualTo(VetoFinding.Source.COMPONENT_INTERNAL);
            assertThat(result.vetoFinding().orElseThrow().reason()).isEqualTo("internal-only veto");
            assertThat(target.internalCheckCalls()).isEqualTo(1);
            assertThat(live.snapshot().maxConcurrentCalls()).isEqualTo(10);
        }

        @Test
        void should_not_consult_internal_check_on_the_cold_path() {
            // What is to be tested: that the dispatcher's cold path bypasses both the listener
            // chain and the internal check. Why: ADR-028 — cold updates apply directly without
            // any veto machinery, regardless of whether listeners or the internal check would
            // have objected.
            // Why important: the cold-path bypass is the property that lets framework
            // bootstrapping (Spring Boot, format-adapter loads) reconfigure components freely
            // before they ever serve a call.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            FakeHandle target = new FakeHandle(LifecycleState.COLD);
            target.setInternalCheck(req -> ChangeDecision.veto("would-veto-but-should-not-run"));

            // When
            DispatchResult result = new UpdateDispatcher().dispatch(KEY, target, live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.PATCHED);
            assertThat(target.internalCheckCalls())
                    .as("internal check must not run on the cold path")
                    .isZero();
            assertThat(live.snapshot().maxConcurrentCalls()).isEqualTo(25);
        }
    }

    @Nested
    @DisplayName("dispatchRemoval")
    class DispatchRemoval {

        @Test
        void should_REMOVE_a_cold_component_without_consulting_the_chain() {
            // What is to be tested: that removal of a cold component skips listeners and the
            // internal check, just like patches do. Why: ADR-028 — cold updates apply directly,
            // and removal is just another kind of update.
            // Why important: format-adapter-driven configuration could remove never-executed
            // components during bootstrap; the chain must not run for them.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            FakeHandle target = new FakeHandle(LifecycleState.COLD);
            target.onChangeRequest(req -> ChangeDecision.veto("would-veto-but-should-not-run"));
            target.setInternalCheck(req -> ChangeDecision.veto("internal-would-veto-but-not-asked"));

            // When
            DispatchResult result = new UpdateDispatcher().dispatchRemoval(KEY, target, live);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.REMOVED);
            assertThat(result.vetoFinding()).isEmpty();
            assertThat(target.internalCheckCalls())
                    .as("cold path bypasses the internal check")
                    .isZero();
        }

        @Test
        void should_REMOVE_a_hot_component_when_no_listener_is_registered() {
            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            FakeHandle target = new FakeHandle(LifecycleState.HOT);

            // When
            DispatchResult result = new UpdateDispatcher().dispatchRemoval(KEY, target, live);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.REMOVED);
            assertThat(result.vetoFinding()).isEmpty();
        }

        @Test
        void should_REMOVE_when_listener_decideRemoval_accepts_and_internal_accepts() {
            // What is to be tested: that the dispatcher iterates listeners' decideRemoval (not
            // decide) and proceeds to apply on full accept. Why: the patch and removal paths
            // share machinery but call distinct listener methods; pinning that decideRemoval is
            // what runs ensures the routing is correct.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            AtomicInteger decideCalls = new AtomicInteger();
            AtomicInteger decideRemovalCalls = new AtomicInteger();
            target.onChangeRequest(new ChangeRequestListener<BulkheadSnapshot>() {
                @Override
                public ChangeDecision decide(ChangeRequest<BulkheadSnapshot> request) {
                    decideCalls.incrementAndGet();
                    return ChangeDecision.accept();
                }

                @Override
                public ChangeDecision decideRemoval(BulkheadSnapshot currentSnapshot) {
                    decideRemovalCalls.incrementAndGet();
                    return ChangeDecision.accept();
                }
            });

            // When
            DispatchResult result = new UpdateDispatcher().dispatchRemoval(KEY, target, live);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.REMOVED);
            assertThat(decideCalls.get())
                    .as("decide(ChangeRequest) is for patches and must not be called on removal")
                    .isZero();
            assertThat(decideRemovalCalls.get()).isEqualTo(1);
            assertThat(target.internalCheckCalls()).isEqualTo(1);
        }

        @Test
        void should_VETO_when_listener_decideRemoval_returns_veto() {
            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            target.onChangeRequest(new ChangeRequestListener<BulkheadSnapshot>() {
                @Override
                public ChangeDecision decide(ChangeRequest<BulkheadSnapshot> request) {
                    return ChangeDecision.accept();
                }

                @Override
                public ChangeDecision decideRemoval(BulkheadSnapshot currentSnapshot) {
                    return ChangeDecision.veto("removal not allowed during business hours");
                }
            });

            // When
            DispatchResult result = new UpdateDispatcher().dispatchRemoval(KEY, target, live);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.VETOED);
            VetoFinding finding = result.vetoFinding().orElseThrow();
            assertThat(finding.componentKey()).isEqualTo(KEY);
            assertThat(finding.source()).isEqualTo(VetoFinding.Source.LISTENER);
            assertThat(finding.reason()).isEqualTo("removal not allowed during business hours");
            assertThat(finding.touchedFields())
                    .as("removal carries no touched fields — the whole component is going away")
                    .isEmpty();
            assertThat(target.internalCheckCalls())
                    .as("internal check must not run on a listener-vetoed removal")
                    .isZero();
        }

        @Test
        void should_VETO_when_internal_check_evaluateRemoval_returns_veto() {
            // What is to be tested: that the component-internal removal gate
            // (InternalMutabilityCheck.evaluateRemoval) can reject a removal the listener chain
            // accepted. Why: ADR-026 defence-in-depth — components have the final say on
            // whether they can be cleanly torn down.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            target.setRemovalCheck(snap -> ChangeDecision.veto("permits in flight"));

            // When
            DispatchResult result = new UpdateDispatcher().dispatchRemoval(KEY, target, live);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.VETOED);
            VetoFinding finding = result.vetoFinding().orElseThrow();
            assertThat(finding.source()).isEqualTo(VetoFinding.Source.COMPONENT_INTERNAL);
            assertThat(finding.reason()).isEqualTo("permits in flight");
            assertThat(target.internalCheckCalls()).isEqualTo(1);
        }

        @Test
        void should_reject_null_arguments() {
            UpdateDispatcher dispatcher = new UpdateDispatcher();
            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));

            assertThatNullPointerException()
                    .isThrownBy(() -> dispatcher.dispatchRemoval(null, target, live))
                    .withMessageContaining("key");
            assertThatNullPointerException()
                    .isThrownBy(() -> dispatcher.dispatchRemoval(KEY, null, live))
                    .withMessageContaining("target");
            assertThatNullPointerException()
                    .isThrownBy(() -> dispatcher.dispatchRemoval(KEY, target, null))
                    .withMessageContaining("live");
        }
    }

    @Nested
    @DisplayName("change request payload")
    class ChangeRequestPayload {

        @Test
        void should_present_current_snapshot_and_proposed_values_to_listeners() {
            // What is to be tested: that the listener receives a ChangeRequest carrying the
            // pre-apply snapshot and the patch's proposed values, with touchedFields matching
            // the patch's touched-set. Why: ADR-028 defines the listener contract on top of
            // ChangeRequest; the dispatcher must populate it correctly so listeners can decide
            // based on the current-versus-proposed delta.
            // Why important: a regression that mis-populates the request would cascade into
            // every listener — they would be deciding against the wrong inputs.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            List<ChangeRequest<BulkheadSnapshot>> seen = new ArrayList<>();
            target.onChangeRequest(req -> {
                seen.add(req);
                return ChangeDecision.accept();
            });

            // When
            new UpdateDispatcher().dispatch(KEY, target, live, patch);

            // Then
            assertThat(seen).hasSize(1);
            ChangeRequest<BulkheadSnapshot> req = seen.get(0);
            assertThat(req.currentSnapshot().maxConcurrentCalls()).isEqualTo(10);
            assertThat(req.touchedFields())
                    .extracting(ComponentField::name)
                    .containsExactly(BulkheadField.MAX_CONCURRENT_CALLS.name());
            assertThat(req.proposedValue(BulkheadField.MAX_CONCURRENT_CALLS, Integer.class))
                    .isEqualTo(25);
            assertThat(req.allProposedValues())
                    .containsEntry(BulkheadField.MAX_CONCURRENT_CALLS, 25);
        }
    }

    @Nested
    @DisplayName("argument validation")
    class ArgumentValidation {

        private final UpdateDispatcher dispatcher = new UpdateDispatcher();
        private final LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
        private final BulkheadPatch patch = new BulkheadPatch();
        private final FakeHandle cold = new FakeHandle(LifecycleState.COLD);

        @Test
        void should_reject_a_null_key() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> dispatcher.dispatch(null, cold, live, patch))
                    .withMessageContaining("key");
        }

        @Test
        void should_reject_a_null_target() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> dispatcher.dispatch(KEY, null, live, patch))
                    .withMessageContaining("target");
        }

        @Test
        void should_reject_a_null_live_container() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> dispatcher.dispatch(KEY, cold, null, patch))
                    .withMessageContaining("live");
        }

        @Test
        void should_reject_a_null_patch() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> dispatcher.dispatch(KEY, cold, live, null))
                    .withMessageContaining("patch");
        }
    }

    @Nested
    @DisplayName("generic patch type")
    class GenericPatchType {

        @Test
        void should_route_an_arbitrary_ComponentPatch_subtype_correctly() {
            // What is to be tested: that the dispatcher's generic signature accepts any
            // ComponentPatch<S>, not only BulkheadPatch. Why: phase 3 will introduce patches for
            // CircuitBreaker, RateLimiter, Retry and TimeLimiter — they must flow through the
            // same dispatcher without changes.
            // Why important: the dispatcher is paradigm- and component-type-agnostic; the test
            // pins that promise so future component additions do not require widening the API.

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(7));
            ComponentPatch<BulkheadSnapshot> setMaxToFive = new ComponentPatch<>() {
                @Override
                public BulkheadSnapshot applyTo(BulkheadSnapshot base) {
                    return new BulkheadSnapshot(
                            base.name(), 5, base.maxWaitDuration(), base.tags(),
                            base.derivedFromPreset(), base.events(), base.strategy());
                }

                @Override
                public Set<? extends ComponentField> touchedFields() {
                    return Set.of();
                }

                @Override
                public Map<ComponentField, Object> proposedValues() {
                    return Map.of();
                }
            };

            // When
            DispatchResult result = new UpdateDispatcher().dispatch(
                    KEY, new FakeHandle(LifecycleState.COLD), live, setMaxToFive);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.PATCHED);
            assertThat(live.snapshot().maxConcurrentCalls()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("absorbed listener / internal-check throws (TODO sub-step 2)")
    class AbsorbedThrows {

        @Test
        void should_VETO_with_LISTENER_source_when_a_patch_listener_throws_a_runtime_exception() {
            // What is to be tested: a listener that throws RuntimeException (instead of returning
            // a clean Veto) is absorbed as a synthetic veto with Source.LISTENER. The patch is
            // rejected for the affected component, the live snapshot is unchanged, and the synthetic
            // reason names the thrown exception type and message.
            // Why successful: outcome=VETOED, source=LISTENER, reason mentions both "listener threw"
            // and the exception class+message; touchedFields equal the patch's touched-set; live
            // snapshot still reads the pre-dispatch value.
            // Why important: pins variant (b) of the TODO entry — listener bugs do NOT take down
            // runtime.update for unrelated components. Cross-component-atomicity (ADR-026) and the
            // conjunctive veto chain (ADR-028) depend on this absorption.
            CapturingLoggerFactory captured = new CapturingLoggerFactory();

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            target.onChangeRequest(req -> {
                throw new IllegalStateException("boom");
            });

            // When
            DispatchResult result =
                    new UpdateDispatcher(captured).dispatch(KEY, target, live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.VETOED);
            VetoFinding finding = result.vetoFinding().orElseThrow();
            assertThat(finding.source()).isEqualTo(VetoFinding.Source.LISTENER);
            assertThat(finding.componentKey()).isEqualTo(KEY);
            assertThat(finding.reason())
                    .contains("listener threw")
                    .contains("IllegalStateException")
                    .contains("boom");
            assertThat(finding.touchedFields())
                    .extracting(ComponentField::name)
                    .containsExactly(BulkheadField.MAX_CONCURRENT_CALLS.name());
            assertThat(live.snapshot().maxConcurrentCalls())
                    .as("absorbed-throw veto must not be applied")
                    .isEqualTo(10);
        }

        @Test
        void should_VETO_with_LISTENER_source_when_a_removal_listener_throws_a_runtime_exception() {
            // What is to be tested: a listener whose decideRemoval throws is absorbed as a
            // synthetic veto on the removal path, mirroring the patch path. The removal is
            // rejected, touchedFields is empty (matches the real-veto removal contract), and the
            // live container's component remains addressable.
            // Why important: removal-path absorption is the second of the four throw sites the
            // sub-step pins. Without it a removal-time listener bug would propagate out of
            // runtime.update and break cross-component-atomicity for removal waves.
            CapturingLoggerFactory captured = new CapturingLoggerFactory();

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            target.onChangeRequest(new ChangeRequestListener<BulkheadSnapshot>() {
                @Override
                public ChangeDecision decide(ChangeRequest<BulkheadSnapshot> request) {
                    return ChangeDecision.accept();
                }

                @Override
                public ChangeDecision decideRemoval(BulkheadSnapshot currentSnapshot) {
                    throw new IllegalStateException("removal-time bug");
                }
            });

            // When
            DispatchResult result =
                    new UpdateDispatcher(captured).dispatchRemoval(KEY, target, live);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.VETOED);
            VetoFinding finding = result.vetoFinding().orElseThrow();
            assertThat(finding.source()).isEqualTo(VetoFinding.Source.LISTENER);
            assertThat(finding.componentKey()).isEqualTo(KEY);
            assertThat(finding.reason())
                    .contains("listener threw")
                    .contains("IllegalStateException")
                    .contains("removal-time bug");
            assertThat(finding.touchedFields())
                    .as("removal carries no touched fields, even on an absorbed-throw veto")
                    .isEmpty();
            assertThat(target.internalCheckCalls())
                    .as("internal check must not run once a listener has thrown")
                    .isZero();
        }

        @Test
        void should_VETO_with_COMPONENT_INTERNAL_source_when_the_patch_internal_check_throws() {
            // What is to be tested: the component-internal mutability check throwing on a patch
            // path is absorbed as a synthetic veto with Source.COMPONENT_INTERNAL. The patch is
            // rejected and the live snapshot is unchanged.
            // Why important: third throw site. The internal check is the last gate; a throw here
            // must not bubble up because that would re-create the cross-component-atomicity break
            // the sub-step closes — even though the bug surface is per-component code, the failure
            // mode is identical to a listener throw.
            CapturingLoggerFactory captured = new CapturingLoggerFactory();

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            target.setInternalCheck(req -> {
                throw new IllegalArgumentException("invariant violated");
            });

            // When
            DispatchResult result =
                    new UpdateDispatcher(captured).dispatch(KEY, target, live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.VETOED);
            VetoFinding finding = result.vetoFinding().orElseThrow();
            assertThat(finding.source()).isEqualTo(VetoFinding.Source.COMPONENT_INTERNAL);
            assertThat(finding.reason())
                    .contains("internal mutability check threw")
                    .contains("IllegalArgumentException")
                    .contains("invariant violated");
            assertThat(finding.touchedFields())
                    .extracting(ComponentField::name)
                    .containsExactly(BulkheadField.MAX_CONCURRENT_CALLS.name());
            assertThat(live.snapshot().maxConcurrentCalls()).isEqualTo(10);
        }

        @Test
        void should_VETO_with_COMPONENT_INTERNAL_source_when_the_removal_internal_check_throws() {
            // What is to be tested: the internal-check throw absorbed on the removal path,
            // mirroring the patch-path internal-check absorption above. Source is
            // COMPONENT_INTERNAL, touchedFields is empty.
            // Why important: fourth and final throw site. Pinning all four sites pins the full
            // cross-product (listener × {patch, removal} ∪ internal-check × {patch, removal}).
            CapturingLoggerFactory captured = new CapturingLoggerFactory();

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            target.setRemovalCheck(snap -> {
                throw new IllegalArgumentException("cannot remove now");
            });

            // When
            DispatchResult result =
                    new UpdateDispatcher(captured).dispatchRemoval(KEY, target, live);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.VETOED);
            VetoFinding finding = result.vetoFinding().orElseThrow();
            assertThat(finding.source()).isEqualTo(VetoFinding.Source.COMPONENT_INTERNAL);
            assertThat(finding.reason())
                    .contains("internal mutability check threw")
                    .contains("IllegalArgumentException")
                    .contains("cannot remove now");
            assertThat(finding.touchedFields()).isEmpty();
        }

        @Test
        void should_short_circuit_the_remaining_listeners_when_an_earlier_listener_throws() {
            // What is to be tested: when listener[1] throws mid-chain, listener[0] has already run
            // and listener[2] does NOT run. The synthetic veto preserves the conjunctive-chain
            // short-circuit semantics — a throw is treated identically to a real Veto.
            // Why successful: counters confirm 1, 1, 0; the synthetic veto names listener[1]'s
            // exception, not listener[2]'s (which never ran). Internal check does not run either.
            // Why important: ADR-028's conjunctive-chain contract must apply to synthetic vetoes,
            // not just real ones — otherwise downstream listeners would observe patches the chain
            // already committed to rejecting, which is the bug the chain is designed to prevent.
            CapturingLoggerFactory captured = new CapturingLoggerFactory();

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            AtomicInteger first = new AtomicInteger();
            AtomicInteger second = new AtomicInteger();
            AtomicInteger third = new AtomicInteger();
            target.onChangeRequest(req -> {
                first.incrementAndGet();
                return ChangeDecision.accept();
            });
            target.onChangeRequest(req -> {
                second.incrementAndGet();
                throw new IllegalStateException("middle listener bug");
            });
            target.onChangeRequest(req -> {
                third.incrementAndGet();
                return ChangeDecision.accept();
            });

            // When
            DispatchResult result =
                    new UpdateDispatcher(captured).dispatch(KEY, target, live, patch);

            // Then
            assertThat(result.outcome()).isEqualTo(ApplyOutcome.VETOED);
            assertThat(result.vetoFinding().orElseThrow().source())
                    .isEqualTo(VetoFinding.Source.LISTENER);
            assertThat(result.vetoFinding().orElseThrow().reason())
                    .contains("middle listener bug");
            assertThat(first.get())
                    .as("first listener runs before the throw")
                    .isEqualTo(1);
            assertThat(second.get())
                    .as("throwing listener was invoked exactly once")
                    .isEqualTo(1);
            assertThat(third.get())
                    .as("listeners after the throw must not be called")
                    .isZero();
            assertThat(target.internalCheckCalls())
                    .as("internal check must not run once a listener has thrown")
                    .isZero();
        }

        @Test
        void should_propagate_an_Error_thrown_by_a_listener_unchanged() {
            // What is to be tested: an Error (e.g. OutOfMemoryError, StackOverflowError) thrown
            // from a listener is NOT caught and absorbed — it propagates out of dispatch. Errors
            // signal JVM-level conditions that synthetic-veto absorption would mask, so the
            // catch block intentionally targets `Exception` only.
            // Why successful: assertThatThrownBy observes the original Error; outcome is never
            // produced because the exception propagates.
            // Why important: the boundary between Exception (absorb) and Error (propagate) is
            // load-bearing. A regression that broadened to `catch (Throwable)` would silently
            // hide OOM-class failures under a synthetic veto, which is exactly the kind of
            // catastrophic-condition masking the JVM's Error subhierarchy is designed to avoid.
            CapturingLoggerFactory captured = new CapturingLoggerFactory();

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            target.onChangeRequest(req -> {
                throw new OutOfMemoryError("simulated");
            });

            UpdateDispatcher dispatcher = new UpdateDispatcher(captured);

            // When / Then
            assertThatThrownBy(() -> dispatcher.dispatch(KEY, target, live, patch))
                    .isInstanceOf(OutOfMemoryError.class)
                    .hasMessage("simulated");
            assertThat(captured.errorEntries)
                    .as("Errors must not be absorbed and so must not be logged as synthetic vetoes")
                    .isEmpty();
            assertThat(live.snapshot().maxConcurrentCalls())
                    .as("the patch was not applied — Error short-circuited the chain before apply")
                    .isEqualTo(10);
        }

        @Test
        void should_log_at_error_level_when_a_listener_throws() {
            // What is to be tested: an absorbed listener throw is logged through the runtime's
            // LoggerFactory at error level. The captured entry references both the listener role
            // and the original exception (the throwable is passed as the trailing varargs arg, so
            // an SLF4J-backed LoggerFactory in production logs the full stack trace; the
            // capturing fixture pins that the throwable arrives in the args array).
            // Why important: traceability. The TODO entry's variant (b) requires that operators
            // can trace an absorbed throw to its source — a synthetic veto reason without a log
            // line would leave them with no stack trace.
            CapturingLoggerFactory captured = new CapturingLoggerFactory();

            // Given
            LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot(10));
            BulkheadPatch patch = new BulkheadPatch();
            patch.touchMaxConcurrentCalls(25);

            FakeHandle target = new FakeHandle(LifecycleState.HOT);
            IllegalStateException original = new IllegalStateException("traceable");
            target.onChangeRequest(req -> {
                throw original;
            });

            // When
            new UpdateDispatcher(captured).dispatch(KEY, target, live, patch);

            // Then
            assertThat(captured.errorEntries).hasSize(1);
            CapturedLogEntry entry = captured.errorEntries.get(0);
            assertThat(entry.format)
                    .contains("absorbed as synthetic veto");
            assertThat(entry.args)
                    .as("the original throwable is included in the args so SLF4J logs the stack trace")
                    .contains((Object) original);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Test fixtures
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Logger factory whose error channel records every log call into {@link #errorEntries}. The
     * other channels are wired to no-op so unrelated logs do not pollute assertions. Modeled on
     * the {@code CapturingLoggerFactory} pattern used by
     * {@code BulkheadHotPhaseFailureModeTest} in the imperative module — kept as a private inner
     * class because there is no project-wide log-capture fixture today.
     */
    private static final class CapturingLoggerFactory implements LoggerFactory {

        final List<CapturedLogEntry> errorEntries = new ArrayList<>();

        @Override
        public Logger getLogger(Class<?> clazz) {
            return new Logger(
                    Logger.NO_OP_ACTION,
                    Logger.NO_OP_ACTION,
                    Logger.NO_OP_ACTION,
                    new CapturingLogAction(errorEntries));
        }
    }

    private record CapturedLogEntry(String format, Object[] args) {
    }

    private static final class CapturingLogAction implements LogAction {

        private final List<CapturedLogEntry> sink;

        CapturingLogAction(List<CapturedLogEntry> sink) {
            this.sink = sink;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void log(String message) {
            sink.add(new CapturedLogEntry(message, new Object[0]));
        }

        @Override
        public void log(String message, Object arg) {
            sink.add(new CapturedLogEntry(message, new Object[]{arg}));
        }

        @Override
        public void log(String message, Object arg1, Object arg2) {
            sink.add(new CapturedLogEntry(message, new Object[]{arg1, arg2}));
        }

        @Override
        public void log(String message, Object arg1, Object arg2, Object arg3) {
            sink.add(new CapturedLogEntry(message, new Object[]{arg1, arg2, arg3}));
        }

        @Override
        public void log(String message, Supplier<?> argSupplier) {
            sink.add(new CapturedLogEntry(message, new Object[]{argSupplier.get()}));
        }

        @Override
        public void log(String message, Object... args) {
            sink.add(new CapturedLogEntry(message, args.clone()));
        }
    }
}
