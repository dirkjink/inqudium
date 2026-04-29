package eu.inqudium.imperative.runtime;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.lifecycle.ChangeDecision;
import eu.inqudium.config.lifecycle.LifecycleState;
import eu.inqudium.config.runtime.ComponentKey;
import eu.inqudium.config.runtime.ImperativeBulkhead;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.validation.ApplyOutcome;
import eu.inqudium.config.validation.BuildReport;
import eu.inqudium.config.validation.VetoFinding;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct public-API tests for the listener-registration surface on
 * {@link eu.inqudium.config.runtime.BulkheadHandle BulkheadHandle} as exposed via
 * {@link ImperativeBulkhead}. The dispatcher unit tests in {@code UpdateDispatcherTest} pin the
 * routing semantics with synthetic handles; the runtime end-to-end tests in
 * {@code RuntimeUpdateTest} pin per-component outcomes including listener vetoes. This class
 * pins the registration handle's own contract — registration, unregistration via the returned
 * {@link AutoCloseable}, idempotent close, listener survival across multiple updates, and the
 * multi-listener iteration order — through the same path application code uses.
 */
@DisplayName("BulkheadHandle listener API")
class BulkheadHandleListenerTest {

    private static final ComponentKey KEY =
            new ComponentKey("inventory", ImperativeTag.INSTANCE);

    private static final InternalExecutor<String, String> IDENTITY =
            (chainId, callId, argument) -> argument;

    /**
     * Build a runtime with a single bulkhead and warm it up so subsequent updates take the hot
     * path. Returns the live handle. Callers are responsible for closing the runtime.
     */
    private static HotBulkhead newHotBulkhead() {
        InqRuntime runtime = Inqudium.configure()
                .imperative(im -> im.bulkhead("inventory",
                        b -> b.balanced().maxConcurrentCalls(15)))
                .build();
        @SuppressWarnings("unchecked")
        InqBulkhead<String, String> bh =
                (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
        bh.execute(1L, 1L, "warm", IDENTITY);
        return new HotBulkhead(runtime, bh);
    }

    /** Pair of {@link InqRuntime} and an already-warmed bulkhead handle. */
    private record HotBulkhead(InqRuntime runtime, ImperativeBulkhead bulkhead) {
    }

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        void should_consult_a_registered_listener_on_a_subsequent_update() {
            // What is to be tested: that a listener registered through the public handle API is
            // reached by a real runtime.update call. Why: the contract is that
            // bh.onChangeRequest(listener) installs the listener for future hot patches; this
            // pins the integration end-to-end.
            // Why important: a regression in any layer of the wiring (handle → lifecycle base →
            // dispatcher) would silently make registrations no-ops. The dispatcher unit tests
            // would still pass.

            try (InqRuntime runtime = newHotBulkhead().runtime) {
                ImperativeBulkhead bh = runtime.imperative().bulkhead("inventory");
                assertThat(bh.lifecycleState()).isEqualTo(LifecycleState.HOT);
                AtomicInteger calls = new AtomicInteger();
                bh.onChangeRequest(req -> {
                    calls.incrementAndGet();
                    return ChangeDecision.accept();
                });

                // When
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(40))));

                // Then
                assertThat(calls.get()).isEqualTo(1);
                assertThat(bh.snapshot().maxConcurrentCalls()).isEqualTo(40);
            }
        }

        @Test
        void should_block_a_patch_via_a_listener_veto_through_the_handle_API() {
            // Given
            try (InqRuntime runtime = newHotBulkhead().runtime) {
                ImperativeBulkhead bh = runtime.imperative().bulkhead("inventory");
                bh.onChangeRequest(req -> ChangeDecision.veto("policy disallows"));

                // When
                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(99))));

                // Then
                assertThat(report.componentOutcomes())
                        .containsEntry(KEY, ApplyOutcome.VETOED);
                assertThat(report.vetoFindings()).hasSize(1);
                VetoFinding finding = report.vetoFindings().get(0);
                assertThat(finding.componentKey()).isEqualTo(KEY);
                assertThat(finding.reason()).isEqualTo("policy disallows");
                assertThat(finding.source()).isEqualTo(VetoFinding.Source.LISTENER);
                assertThat(bh.snapshot().maxConcurrentCalls()).isEqualTo(15);
            }
        }
    }

    @Nested
    @DisplayName("unregistration via AutoCloseable")
    class Unregistration {

        @Test
        void should_stop_consulting_a_listener_after_close() throws Exception {
            // Given
            try (InqRuntime runtime = newHotBulkhead().runtime) {
                ImperativeBulkhead bh = runtime.imperative().bulkhead("inventory");
                AtomicInteger calls = new AtomicInteger();
                AutoCloseable handle = bh.onChangeRequest(req -> {
                    calls.incrementAndGet();
                    return ChangeDecision.accept();
                });

                // First update — listener consulted.
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(40))));
                assertThat(calls.get()).isEqualTo(1);

                // When — unregister, then update again.
                handle.close();
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(50))));

                // Then — counter is unchanged (listener no longer consulted), patch applied.
                assertThat(calls.get()).isEqualTo(1);
                assertThat(bh.snapshot().maxConcurrentCalls()).isEqualTo(50);
            }
        }

        @Test
        void close_should_be_idempotent() throws Exception {
            // What is to be tested: that calling close() twice on the registration handle is
            // harmless. Why: ListenerRegistry's contract documents this — "closing it twice is
            // harmless". Pinning it makes the contract executable.
            // Why important: client code holding the AutoCloseable in a try-with-resources or
            // a defensive cleanup block may close it once and then close it again on shutdown
            // without bookkeeping. A regression that threw on the second close would be a
            // surprise at production teardown time.

            try (InqRuntime runtime = newHotBulkhead().runtime) {
                ImperativeBulkhead bh = runtime.imperative().bulkhead("inventory");
                AutoCloseable handle = bh.onChangeRequest(req -> ChangeDecision.accept());

                // When / Then — neither close throws.
                handle.close();
                handle.close();

                // And a subsequent update still applies cleanly without the listener.
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(40))));
                assertThat(bh.snapshot().maxConcurrentCalls()).isEqualTo(40);
            }
        }

        @Test
        void closing_one_handle_should_not_affect_other_listeners() throws Exception {
            // What is to be tested: that unregistering one listener leaves any other registered
            // listeners untouched. Why: the underlying CopyOnWriteArrayList removes by value,
            // so two distinct lambdas occupy distinct slots — pinning that the close() of one
            // does not collateral-damage another.
            // Why important: applications often layer policy listeners (one for SLA limits,
            // one for safety guards). A bug that removed all listeners on a single close would
            // silently drop the safety guard.

            try (InqRuntime runtime = newHotBulkhead().runtime) {
                ImperativeBulkhead bh = runtime.imperative().bulkhead("inventory");
                AtomicInteger surviving = new AtomicInteger();
                AutoCloseable goingAway = bh.onChangeRequest(req -> ChangeDecision.accept());
                bh.onChangeRequest(req -> {
                    surviving.incrementAndGet();
                    return ChangeDecision.accept();
                });

                // When
                goingAway.close();
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(40))));

                // Then
                assertThat(surviving.get())
                        .as("the surviving listener must still be consulted exactly once")
                        .isEqualTo(1);
                assertThat(bh.snapshot().maxConcurrentCalls()).isEqualTo(40);
            }
        }
    }

    @Nested
    @DisplayName("listener survival")
    class ListenerSurvival {

        @Test
        void should_survive_three_consecutive_accept_updates() {
            // What is to be tested: that a single registration is consulted on every subsequent
            // hot update — registrations are sticky, not one-shot. Why: snapshot replacement
            // through LiveContainer.apply must not collaterally remove listeners; the listener
            // list is owned by the lifecycle base, not by the snapshot. Pinning three updates
            // (rather than one) forces the test to fail loudly if a regression unregistered
            // after first apply.
            // Why important: production policies (e.g. "no growth past 50") need to be
            // evaluated for every update, not just the first one after registration.

            try (InqRuntime runtime = newHotBulkhead().runtime) {
                ImperativeBulkhead bh = runtime.imperative().bulkhead("inventory");
                AtomicInteger calls = new AtomicInteger();
                bh.onChangeRequest(req -> {
                    calls.incrementAndGet();
                    return ChangeDecision.accept();
                });

                // When — three updates in a row.
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(20))));
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(30))));
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(40))));

                // Then
                assertThat(calls.get()).isEqualTo(3);
                assertThat(bh.snapshot().maxConcurrentCalls()).isEqualTo(40);
            }
        }
    }

    @Nested
    @DisplayName("multiple listeners")
    class MultipleListeners {

        @Test
        void should_invoke_listeners_in_registration_order() {
            // What is to be tested: that the dispatcher consults listeners in the order they
            // were registered via the public handle API. Why: ADR-028 promises preserved
            // registration order; the underlying CopyOnWriteArrayList preserves it too.
            // Why important: layered policies often assume an ordering ("safety limits before
            // SLA tweaks"); a regression silently shuffling order would invert priorities.
            //
            // The spy listeners append their position to a shared list. Both accept so every
            // listener runs (the conjunctive chain stops at the first veto, which would mask
            // ordering for the suffix).

            try (InqRuntime runtime = newHotBulkhead().runtime) {
                ImperativeBulkhead bh = runtime.imperative().bulkhead("inventory");
                List<String> invocationOrder = new ArrayList<>();
                bh.onChangeRequest(req -> {
                    invocationOrder.add("first");
                    return ChangeDecision.accept();
                });
                bh.onChangeRequest(req -> {
                    invocationOrder.add("second");
                    return ChangeDecision.accept();
                });
                bh.onChangeRequest(req -> {
                    invocationOrder.add("third");
                    return ChangeDecision.accept();
                });

                // When
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(40))));

                // Then
                assertThat(invocationOrder).containsExactly("first", "second", "third");
                assertThat(bh.snapshot().maxConcurrentCalls()).isEqualTo(40);
            }
        }

        @Test
        void should_short_circuit_at_the_first_listener_to_veto() {
            // What is to be tested: that the conjunctive chain visible at the dispatcher is
            // also the chain visible from the handle API — the first registered veto wins, and
            // the second listener is never asked. Why: pin the contract end-to-end so that the
            // handle API and the dispatcher share semantics.

            try (InqRuntime runtime = newHotBulkhead().runtime) {
                ImperativeBulkhead bh = runtime.imperative().bulkhead("inventory");
                AtomicInteger second = new AtomicInteger();
                bh.onChangeRequest(req -> ChangeDecision.veto("first says no"));
                bh.onChangeRequest(req -> {
                    second.incrementAndGet();
                    return ChangeDecision.accept();
                });

                // When
                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(40))));

                // Then
                assertThat(report.vetoFindings()).hasSize(1);
                assertThat(report.vetoFindings().get(0).reason()).isEqualTo("first says no");
                assertThat(second.get())
                        .as("second listener must not be consulted once the first has vetoed")
                        .isZero();
                assertThat(bh.snapshot().maxConcurrentCalls()).isEqualTo(15);
            }
        }
    }
}
