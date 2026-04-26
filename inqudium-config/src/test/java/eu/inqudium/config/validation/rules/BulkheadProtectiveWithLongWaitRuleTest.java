package eu.inqudium.config.validation.rules;

import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.validation.Severity;
import eu.inqudium.config.validation.ValidationFinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BulkheadProtectiveWithLongWaitRule")
class BulkheadProtectiveWithLongWaitRuleTest {

    private static BulkheadSnapshot bulkhead(int maxConcurrent, Duration wait, String preset) {
        return new BulkheadSnapshot(
                "inventory", maxConcurrent, wait, Set.of(), preset, BulkheadEventConfig.disabled());
    }

    private final BulkheadProtectiveWithLongWaitRule rule =
            new BulkheadProtectiveWithLongWaitRule();

    @Test
    void should_apply_to_bulkhead_snapshot() {
        assertThat(rule.appliesTo()).isEqualTo(BulkheadSnapshot.class);
        assertThat(rule.defaultSeverity()).isEqualTo(Severity.WARNING);
        assertThat(rule.ruleId()).isEqualTo("BULKHEAD_PROTECTIVE_WITH_LONG_WAIT");
    }

    @Test
    void should_not_fire_when_no_preset_is_set() {
        // What is to be tested: that snapshots without a preset baseline never trigger this
        // rule, regardless of their wait duration. Why: the rule is about a mismatch between
        // preset intent and configured value; without a preset there is no intent to mismatch.

        // Given
        BulkheadSnapshot s = bulkhead(10, Duration.ofSeconds(5), null);

        // When / Then
        assertThat(rule.check(s)).isEqualTo(Optional.empty());
    }

    @Test
    void should_not_fire_when_preset_is_balanced() {
        // Given
        BulkheadSnapshot s = bulkhead(50, Duration.ofMillis(500), "balanced");

        // When / Then
        assertThat(rule.check(s)).isEmpty();
    }

    @Test
    void should_not_fire_when_preset_is_permissive() {
        // Given
        BulkheadSnapshot s = bulkhead(200, Duration.ofSeconds(5), "permissive");

        // When / Then
        assertThat(rule.check(s)).isEmpty();
    }

    @Test
    void should_not_fire_when_protective_with_zero_wait() {
        // The protective preset's own baseline.
        // Given
        BulkheadSnapshot s = bulkhead(10, Duration.ZERO, "protective");

        // When / Then
        assertThat(rule.check(s)).isEmpty();
    }

    @Test
    void should_not_fire_when_protective_with_short_wait_at_threshold() {
        // The threshold itself does not trigger — only durations strictly greater than 100ms.
        // Given
        BulkheadSnapshot s = bulkhead(10, Duration.ofMillis(100), "protective");

        // When / Then
        assertThat(rule.check(s)).isEmpty();
    }

    @Test
    void should_fire_when_protective_with_long_wait() {
        // Given
        BulkheadSnapshot s = bulkhead(10, Duration.ofSeconds(2), "protective");

        // When
        Optional<ValidationFinding> finding = rule.check(s);

        // Then
        assertThat(finding).isPresent();
        ValidationFinding f = finding.get();
        assertThat(f.ruleId()).isEqualTo("BULKHEAD_PROTECTIVE_WITH_LONG_WAIT");
        assertThat(f.severity()).isEqualTo(Severity.WARNING);
        assertThat(f.componentName()).isEqualTo("inventory");
        assertThat(f.message())
                .contains("'inventory'")
                .contains("protective")
                .contains("PT2S");
    }

    @Test
    void should_fire_just_above_the_threshold() {
        // Given
        BulkheadSnapshot s = bulkhead(10, Duration.ofMillis(101), "protective");

        // When / Then
        assertThat(rule.check(s)).isPresent();
    }
}
