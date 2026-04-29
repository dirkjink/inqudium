package eu.inqudium.config.runtime;

import eu.inqudium.config.lifecycle.ChangeRequest;
import eu.inqudium.config.patch.BulkheadPatch;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the {@link ChangeRequest#postPatchSnapshot()} contract introduced in
 * REFACTORING.md 2.10.D. The post-patch snapshot is what the dispatcher precomputes once and
 * hands every listener and the component-internal mutability check; the contract is identity
 * with {@code patch.applyTo(currentSnapshot)} and idempotence across reads.
 */
@DisplayName("DefaultChangeRequest.postPatchSnapshot")
class DefaultChangeRequestPostPatchSnapshotTest {

    private static BulkheadSnapshot baseSnapshot() {
        return new BulkheadSnapshot(
                "inventory", 10, Duration.ofMillis(100), Set.of(), null,
                BulkheadEventConfig.disabled(), new SemaphoreStrategyConfig());
    }

    @Test
    void should_equal_patch_applyTo_currentSnapshot() {
        // Given
        BulkheadSnapshot current = baseSnapshot();
        BulkheadPatch patch = new BulkheadPatch();
        patch.touchMaxConcurrentCalls(42);
        BulkheadSnapshot expected = patch.applyTo(current);

        // When
        ChangeRequest<BulkheadSnapshot> request = new DefaultChangeRequest<>(
                current, patch.applyTo(current), patch.touchedFields(),
                patch.proposedValues());

        // Then
        assertThat(request.postPatchSnapshot()).isEqualTo(expected);
        assertThat(request.postPatchSnapshot().maxConcurrentCalls()).isEqualTo(42);
    }

    @Test
    void should_be_idempotent_across_repeated_reads() {
        BulkheadSnapshot current = baseSnapshot();
        BulkheadPatch patch = new BulkheadPatch();
        patch.touchMaxConcurrentCalls(42);

        ChangeRequest<BulkheadSnapshot> request = new DefaultChangeRequest<>(
                current, patch.applyTo(current), patch.touchedFields(),
                patch.proposedValues());

        // Two reads return the same instance — the request stores the precomputed snapshot
        // rather than recomputing applyTo on every consultation.
        assertThat(request.postPatchSnapshot()).isSameAs(request.postPatchSnapshot());
    }

    @Test
    void should_carry_inherited_fields_for_an_untouched_field_through_to_the_post_patch() {
        // Pinning the partial-patch contract: untouched fields inherit from currentSnapshot.
        BulkheadSnapshot current = baseSnapshot();
        BulkheadPatch patch = new BulkheadPatch();
        patch.touchMaxConcurrentCalls(99);

        ChangeRequest<BulkheadSnapshot> request = new DefaultChangeRequest<>(
                current, patch.applyTo(current), patch.touchedFields(),
                patch.proposedValues());

        BulkheadSnapshot post = request.postPatchSnapshot();
        assertThat(post.maxConcurrentCalls()).isEqualTo(99);
        assertThat(post.maxWaitDuration()).isEqualTo(current.maxWaitDuration());
        assertThat(post.events()).isEqualTo(current.events());
        assertThat(post.strategy()).isEqualTo(current.strategy());
    }
}
