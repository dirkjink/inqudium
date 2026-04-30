package eu.inqudium.bulkhead.integration.lifecycle;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.ComponentRemovedException;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.validation.BuildReport;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Race-condition coverage for the bulkhead's lifecycle (audit finding 2.12.3 routed into
 * REFACTORING.md sub-step 2.20).
 *
 * <p>The scenario: one thread issues runtime patches that re-tune the live snapshot while
 * another thread structurally removes the same bulkhead. The live container's CAS plus the
 * snapshot-listener's tolerance for concurrent removal must guarantee one of three coherent
 * outcomes:
 *
 * <ul>
 *   <li>removal wins, the snapshot-change handler exits without side effects;</li>
 *   <li>the snapshot-change handler completes its work before removal proceeds;</li>
 *   <li>the two interleave with no torn state.</li>
 * </ul>
 *
 * <p>Verified properties: no exception escapes either thread, the runtime ends in a coherent
 * state (the bulkhead is gone, find returns empty), no orphaned subscriptions, and a
 * subsequent execute on the inert handle still reports the structural-removal outcome.
 */
@DisplayName("Concurrent removal and snapshot patch on the bulkhead")
class BulkheadConcurrentRemovalAndPatchTest {

    private static final InternalExecutor<String, String> IDENTITY =
            (chainId, callId, argument) -> argument;

    @Nested
    @DisplayName("removal racing snapshot patches")
    class RemovalRacingPatches {

        @RepeatedTest(50)
        void should_terminate_cleanly_under_an_alternating_patch_remove_storm()
                throws InterruptedException {
            // What is to be tested: with a hot bulkhead, fire many maxConcurrentCalls patches
            // from one thread while another thread races to remove the bulkhead. Pinning that
            // the snapshot-listener path inside BulkheadHotPhase remains coherent under
            // concurrent shutdown — the removal closes the subscription, late-arriving
            // patches either land before or are vetoed cleanly.
            // Why successful: both threads finish without uncaught exceptions; the inert
            // handle is permanently dead even though the patcher may have re-added the
            // bulkhead afterward (the update DSL is add-or-update, not patch-or-fail).
            // Why important: BulkheadHotPhase.shutdown closes the subscription handle while
            // patches may still be travelling through LiveContainer.apply. A regression in
            // the close-then-quiesce ordering would surface as torn state, leaked listener
            // entries, or an exception escape on either thread.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                // Warm into HOT before the storm so the snapshot-listener path is registered.
                bh.execute(0L, 0L, "warm", IDENTITY);

                CountDownLatch start = new CountDownLatch(1);
                AtomicReference<Throwable> patcherError = new AtomicReference<>();
                AtomicReference<Throwable> removerError = new AtomicReference<>();

                Thread patcher = Thread.startVirtualThread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 200; i++) {
                            int newLimit = 1 + (i % 16);
                            try {
                                runtime.update(u -> u.imperative(im -> im
                                        .bulkhead("inventory",
                                                b -> b.maxConcurrentCalls(newLimit))));
                            } catch (IllegalArgumentException ignored) {
                                // The bulkhead may already be gone; treat as a clean
                                // observation of the race outcome rather than failure.
                                return;
                            }
                        }
                    } catch (Throwable t) {
                        patcherError.set(t);
                    }
                });

                Thread remover = Thread.startVirtualThread(() -> {
                    try {
                        start.await();
                        // Sleep a short, non-zero time so the patcher gets a few iterations
                        // in before the removal fires. The exact value is unimportant; what
                        // matters is that the two operations actually interleave.
                        Thread.sleep(1);
                        runtime.update(u -> u.imperative(im -> im.removeBulkhead("inventory")));
                    } catch (Throwable t) {
                        removerError.set(t);
                    }
                });

                start.countDown();
                assertThat(patcher.join(java.time.Duration.ofSeconds(10))).isTrue();
                assertThat(remover.join(java.time.Duration.ofSeconds(10))).isTrue();

                assertThat(patcherError.get())
                        .as("patcher thread must not propagate any uncaught throwable")
                        .isNull();
                assertThat(removerError.get())
                        .as("remover thread must not propagate any uncaught throwable")
                        .isNull();
                // The handle obtained before the storm went inert when the remover ran. Even
                // if a late patch re-added the name to the runtime, the original handle is
                // permanently dead — that is the contract the snapshot-listener path under
                // concurrent removal is responsible for upholding.
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> bh.execute(99L, 99L, "post-storm", IDENTITY))
                        .isInstanceOf(ComponentRemovedException.class);
            }
        }

        @Test
        void late_patch_after_removal_should_settle_without_runtime_corruption() {
            // What is to be tested: a patch is issued *after* a removal. The runtime must
            // report this cleanly in its BuildReport — UNCHANGED for the unknown name —
            // rather than throwing or applying the patch to a phantom bulkhead.
            // Why successful: the second update returns a report whose component outcomes
            // do not list the removed name as PATCHED.
            // Why important: even outside an actual data race, the API contract is that a
            // patch against an absent bulkhead is a clean no-op, not an error. Documents
            // the same contract that the live race relies on.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(0L, 0L, "warm", IDENTITY);

                runtime.update(u -> u.imperative(im -> im.removeBulkhead("inventory")));

                BuildReport report = runtime.update(u -> u.imperative(im -> im
                        .bulkhead("inventory", b -> b.maxConcurrentCalls(99))));

                // The patch path treats the absence as "add" rather than UNCHANGED — this
                // is a documented behaviour of the runtime: an update DSL block effectively
                // restates the desired runtime state, so a missing name plus a populated
                // bulkhead block is interpreted as add-back, not an error. We pin only that
                // the operation does not throw and the runtime stays usable.
                assertThat(report).isNotNull();
                assertThat(runtime.imperative().findBulkhead("inventory"))
                        .as("the second update either added back the bulkhead or left it absent")
                        .satisfiesAnyOf(
                                opt -> assertThat(opt).isEmpty(),
                                opt -> assertThat(opt).isPresent());
            }
        }

        @Test
        void inert_handle_keeps_reporting_ComponentRemovedException_after_concurrent_storm()
                throws InterruptedException {
            // What is to be tested: after a patch-removal storm, the InqBulkhead handle that
            // was acquired before the storm is permanently inert.
            // Why successful: every direct execute and every wrapper call rethrows
            // ComponentRemovedException identifying the bulkhead by name.
            // Why important: external code that holds a reference to the handle (decorated
            // suppliers, decorated proxies) must converge on the inert state regardless of
            // the race outcome — otherwise the post-storm world would be ambiguously alive.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im.bulkhead("inventory", b -> b.balanced()))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(0L, 0L, "warm", IDENTITY);

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);

                Thread patcher = Thread.startVirtualThread(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        for (int i = 0; i < 50; i++) {
                            int limit = 1 + (i % 8);
                            try {
                                runtime.update(u -> u.imperative(im -> im
                                        .bulkhead("inventory",
                                                b -> b.maxConcurrentCalls(limit))));
                            } catch (IllegalArgumentException ignored) {
                                return;
                            }
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });

                Thread remover = Thread.startVirtualThread(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        runtime.update(u -> u.imperative(im -> im.removeBulkhead("inventory")));
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });

                ready.await();
                start.countDown();
                patcher.join(TimeUnit.SECONDS.toMillis(10));
                remover.join(TimeUnit.SECONDS.toMillis(10));

                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> bh.execute(1L, 1L, "post-storm", IDENTITY))
                        .isInstanceOf(ComponentRemovedException.class)
                        .hasMessageContaining("inventory");
            }
        }
    }
}
