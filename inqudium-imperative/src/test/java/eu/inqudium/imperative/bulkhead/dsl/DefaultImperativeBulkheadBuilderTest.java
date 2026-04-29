package eu.inqudium.imperative.bulkhead.dsl;

import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultImperativeBulkheadBuilder")
class DefaultImperativeBulkheadBuilderTest {

    /**
     * The paradigm-agnostic builder behaviour — setter validation, preset values,
     * preset-then-customize discipline, touch-tracking, fluent return, tag dedup — is
     * exercised by {@code BulkheadBuilderBaseTest} in {@code inqudium-config}. Tests here
     * cover only what is genuinely imperative-specific. In phase 1.5 the imperative subclass
     * adds nothing of its own (no strategy injection, no adaptive-limit sub-builders yet), so
     * a single end-to-end smoke test is the right scope. As paradigm-specific extensions land
     * in later phases, the corresponding tests grow next to them here.
     */

    @Test
    void should_produce_a_valid_bulkhead_snapshot_through_the_imperative_subclass() {
        // Given
        DefaultImperativeBulkheadBuilder builder =
                new DefaultImperativeBulkheadBuilder("inventory");
        BulkheadSnapshot systemDefault = new BulkheadSnapshot(
                "default", 25, Duration.ofMillis(100), Set.of(), null,
                BulkheadEventConfig.disabled(), new SemaphoreStrategyConfig());

        // When
        builder.balanced().maxConcurrentCalls(75).tags("payment", "critical");
        BulkheadSnapshot result = builder.toPatch().applyTo(systemDefault);

        // Then
        assertThat(result.name()).isEqualTo("inventory");
        assertThat(result.maxConcurrentCalls()).isEqualTo(75);
        assertThat(result.maxWaitDuration()).isEqualTo(Duration.ofMillis(500));
        assertThat(result.tags()).containsExactlyInAnyOrder("payment", "critical");
        assertThat(result.derivedFromPreset()).isEqualTo("balanced");
    }
}
