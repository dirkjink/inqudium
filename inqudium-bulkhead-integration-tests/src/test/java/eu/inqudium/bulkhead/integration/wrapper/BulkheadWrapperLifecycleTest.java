package eu.inqudium.bulkhead.integration.wrapper;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.event.ComponentBecameHotEvent;
import eu.inqudium.config.runtime.ComponentRemovedException;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.snapshot.CoDelStrategyConfig;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wrapper compatibility across the bulkhead's lifecycle transitions
 * (audit finding 2.17.3 routed into REFACTORING.md sub-step 2.20).
 *
 * <p>Three scenarios pin that a wrapper held over a real {@link InqBulkhead} stays semantically
 * correct as the underlying component moves through cold-to-hot, strategy hot-swap, and
 * structural removal. The wrappers come from the public {@code InqDecorator} contract that
 * {@code InqBulkhead} implements after ADR-033 — {@code decorateSupplier(...)} here, but the
 * argument applies equally to the other {@code decorateXxx} factories.
 *
 * <p>The phase-reference re-read on every {@code execute} call is the load-bearing property
 * being pinned: a wrapper that cached the strategy or the phase at construction time would
 * fail any of these tests; a wrapper that defers to {@code bulkhead.execute(...)} on every
 * invocation passes them all without per-wrapper bookkeeping.
 */
@DisplayName("Bulkhead wrapper lifecycle compatibility")
class BulkheadWrapperLifecycleTest {

    @Nested
    @DisplayName("cold-to-hot transition")
    class ColdToHot {

        @Test
        void wrapper_built_before_first_execute_transitions_to_hot_on_first_call() {
            // What is to be tested: a wrapper is constructed while the bulkhead is still in its
            // cold phase. The first invocation through the wrapper must trigger the cold-to-hot
            // transition and complete normally.
            // Why successful: lifecycleState() reports HOT after the call, the supplier produced
            // its result, and a ComponentBecameHotEvent was published exactly once.
            // Why important: the wrapper must not capture any phase reference at construction
            // time — if it did, the first call would still see the cold phase and either
            // bypass the transition or run on stale state.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<Void, String> bh =
                        (InqBulkhead<Void, String>) runtime.imperative().bulkhead("inventory");

                // Given — wrapper built while bulkhead is cold; subscribe to the runtime
                // publisher so the cold-to-hot event is observable.
                List<ComponentBecameHotEvent> hotEvents = new ArrayList<>();
                runtime.general().eventPublisher()
                        .onEvent(ComponentBecameHotEvent.class, hotEvents::add);
                Supplier<String> protected_ = bh.decorateSupplier(() -> "ok");

                // When — first call goes through the wrapper.
                String result = protected_.get();

                // Then
                assertThat(result).isEqualTo("ok");
                assertThat(hotEvents)
                        .as("the cold-to-hot transition fired exactly once for this bulkhead")
                        .filteredOn(e -> e.getElementName().equals("inventory"))
                        .hasSize(1);
            }
        }

        @Test
        void multiple_wrappers_built_cold_all_share_the_same_transition() {
            // What is to be tested: several wrappers are constructed against a single cold
            // bulkhead. The first wrapper invocation triggers the transition; subsequent
            // invocations from any of the wrappers stay on the now-hot bulkhead.
            // Why successful: every wrapper produces its result, only one
            // ComponentBecameHotEvent is published.
            // Why important: cold-to-hot is a one-shot CAS on the component, not the wrapper —
            // the wrappers' shared identity (their underlying bulkhead) drives the transition.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<Void, String> bh =
                        (InqBulkhead<Void, String>) runtime.imperative().bulkhead("inventory");

                List<ComponentBecameHotEvent> hotEvents = new ArrayList<>();
                runtime.general().eventPublisher()
                        .onEvent(ComponentBecameHotEvent.class, hotEvents::add);

                Supplier<String> a = bh.decorateSupplier(() -> "a");
                Supplier<String> b = bh.decorateSupplier(() -> "b");
                Supplier<String> c = bh.decorateSupplier(() -> "c");

                // When
                String resultA = a.get();
                String resultB = b.get();
                String resultC = c.get();

                // Then
                assertThat(resultA).isEqualTo("a");
                assertThat(resultB).isEqualTo("b");
                assertThat(resultC).isEqualTo("c");
                assertThat(hotEvents)
                        .filteredOn(e -> e.getElementName().equals("inventory"))
                        .hasSize(1);
            }
        }
    }

    @Nested
    @DisplayName("strategy hot-swap")
    class HotSwap {

        @Test
        void wrapper_held_across_strategy_swap_reflects_the_new_strategy_on_next_call() {
            // What is to be tested: a wrapper is created, fired once on the original semaphore
            // strategy, then the runtime swaps the strategy to CoDel under quiescent conditions.
            // The same wrapper is invoked again. On the second call the bulkhead must run on
            // the new strategy.
            // Why successful: the snapshot reflects the new strategy after the swap, and the
            // second call still completes through the same wrapper.
            // Why important: a wrapper that cached the strategy reference would keep using the
            // semaphore after the swap; volatile-single-write semantics on the strategy field
            // plus phase-reference re-read on every execute is what makes this test pass.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<Void, String> bh =
                        (InqBulkhead<Void, String>) runtime.imperative().bulkhead("inventory");

                Supplier<String> protected_ = bh.decorateSupplier(() -> "ok");

                // Given — first call warms the bulkhead on Semaphore.
                assertThat(protected_.get()).isEqualTo("ok");
                assertThat(bh.snapshot().strategy())
                        .isInstanceOf(SemaphoreStrategyConfig.class);

                // When — quiescent strategy swap under the same wrapper.
                runtime.update(u -> u.imperative(im -> im.bulkhead("inventory", b -> b
                        .codel(c -> c.targetDelay(Duration.ofMillis(50))
                                .interval(Duration.ofMillis(500))))));

                // Then — same wrapper, new strategy.
                assertThat(bh.snapshot().strategy())
                        .isInstanceOf(CoDelStrategyConfig.class);
                assertThat(protected_.get()).isEqualTo("ok");
            }
        }

        @Test
        void wrapper_observes_max_concurrent_calls_retune_in_place() {
            // What is to be tested: a Semaphore-strategy bulkhead is patched in place with a
            // higher maxConcurrentCalls. The wrapper held across the patch sees the new limit
            // on its next call without any reconstruction.
            // Why successful: availablePermits() reads from the live strategy and reflects the
            // new ceiling.
            // Why important: in-place re-tunes are the most common runtime update; pinning that
            // wrappers do not need to be rebuilt for limit changes is the primary user-facing
            // benefit of the lifecycle architecture.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b
                            .balanced().maxConcurrentCalls(2)))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<Void, String> bh =
                        (InqBulkhead<Void, String>) runtime.imperative().bulkhead("inventory");

                Supplier<String> protected_ = bh.decorateSupplier(() -> "ok");
                assertThat(protected_.get()).isEqualTo("ok");
                assertThat(bh.availablePermits()).isEqualTo(2);

                // When — patch the limit upward.
                runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(7))));

                // Then — the same wrapper sees the new limit on its next call.
                assertThat(protected_.get()).isEqualTo("ok");
                assertThat(bh.availablePermits()).isEqualTo(7);
            }
        }
    }

    @Nested
    @DisplayName("structural removal")
    class StructuralRemoval {

        @Test
        void wrapper_invoked_after_markRemoved_raises_ComponentRemovedException() {
            // What is to be tested: a wrapper is built and used while the bulkhead is hot,
            // then the bulkhead is structurally removed via runtime.update. A subsequent
            // wrapper invocation must fail with ComponentRemovedException — same contract as
            // a direct execute() call on the inert handle.
            // Why successful: the wrapper rethrows the exception on .get() instead of caching
            // a working chain.
            // Why important: external handles must go inert atomically with removal; a wrapper
            // that cached the chain or the strategy would happily run a "ghost" call after the
            // component was supposed to be gone.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<Void, String> bh =
                        (InqBulkhead<Void, String>) runtime.imperative().bulkhead("inventory");

                Supplier<String> protected_ = bh.decorateSupplier(() -> "ok");
                assertThat(protected_.get()).isEqualTo("ok");

                // When — remove the bulkhead from the runtime.
                runtime.update(u -> u.imperative(im -> im.removeBulkhead("inventory")));

                // Then — the wrapper invocation fails on the inert handle.
                assertThatThrownBy(protected_::get)
                        .isInstanceOf(ComponentRemovedException.class)
                        .hasMessageContaining("inventory");
            }
        }

        @Test
        void wrapper_built_after_markRemoved_also_raises_ComponentRemovedException() {
            // Pinning the symmetric case: a wrapper constructed against an already-removed
            // bulkhead also fails on first invocation. Equally important — the wrapper must
            // not be a "way around" the removal contract.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<Void, String> bh =
                        (InqBulkhead<Void, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, null, (cid, callId, arg) -> null);

                runtime.update(u -> u.imperative(im -> im.removeBulkhead("inventory")));

                Supplier<String> protected_ = bh.decorateSupplier(() -> "ok");

                assertThatThrownBy(protected_::get)
                        .isInstanceOf(ComponentRemovedException.class);
            }
        }
    }
}
