package eu.inqudium.config.validation.rules;

import eu.inqudium.config.runtime.InqConfigView;
import eu.inqudium.config.runtime.ParadigmTag;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.ComponentSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.config.validation.DiagnosticFinding;
import eu.inqudium.config.validation.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MultipleBulkheadsNoAggregateLimitRule")
class MultipleBulkheadsNoAggregateLimitRuleTest {

    private static BulkheadSnapshot snap(String name, int max) {
        return new BulkheadSnapshot(
                name, max, Duration.ofMillis(100), Set.of(),
                null, BulkheadEventConfig.disabled(), new SemaphoreStrategyConfig());
    }

    private static InqConfigView viewOf(BulkheadSnapshot... snapshots) {
        List<BulkheadSnapshot> copy = List.of(snapshots);
        return new InqConfigView() {
            @Override public GeneralSnapshot general() { throw fail(); }
            @Override public Stream<ComponentSnapshot> all() { throw fail(); }
            @Override public Stream<BulkheadSnapshot> bulkheads() { return copy.stream(); }
            @Override public Optional<BulkheadSnapshot> findBulkhead(
                    String name, ParadigmTag paradigm) { throw fail(); }

            private static UnsupportedOperationException fail() {
                return new UnsupportedOperationException(
                        "rule should only call bulkheads()");
            }
        };
    }

    @Nested
    @DisplayName("emit conditions")
    class EmitConditions {

        @Test
        void should_emit_finding_when_count_and_aggregate_both_exceed_thresholds() {
            // What is to be tested: 6 bulkheads × 50 permits each = 300 aggregate. Both axes
            // (count > 5 AND aggregate > 100) trigger the rule, so a finding is produced.
            // Why important: this is the documented canary case from REFACTORING.md 2.6 — if it
            // does not fire, the rule is broken or the threshold drifted.

            // Given
            MultipleBulkheadsNoAggregateLimitRule rule =
                    new MultipleBulkheadsNoAggregateLimitRule();
            BulkheadSnapshot[] bulkheads = generate(6, 50);

            // When
            List<DiagnosticFinding> findings = rule.check(viewOf(bulkheads));

            // Then
            assertThat(findings).hasSize(1);
            DiagnosticFinding f = findings.get(0);
            assertThat(f.ruleId()).isEqualTo(MultipleBulkheadsNoAggregateLimitRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.affectedComponents()).hasSize(6);
            assertThat(f.context())
                    .containsEntry("bulkheadCount", 6L)
                    .containsEntry("aggregatePermits", 300L);
        }

        @Test
        void should_emit_no_finding_when_count_is_below_threshold() {
            // 2 bulkheads × 50 permits = 100 aggregate. Aggregate matches the boundary (not
            // strictly above), and count is well below — no finding.

            MultipleBulkheadsNoAggregateLimitRule rule =
                    new MultipleBulkheadsNoAggregateLimitRule();

            assertThat(rule.check(viewOf(generate(2, 50)))).isEmpty();
        }

        @Test
        void should_emit_no_finding_when_aggregate_is_below_threshold() {
            // 6 bulkheads × 5 permits = 30 aggregate. Count is above its threshold but
            // aggregate is well below 100 — no finding.

            MultipleBulkheadsNoAggregateLimitRule rule =
                    new MultipleBulkheadsNoAggregateLimitRule();

            assertThat(rule.check(viewOf(generate(6, 5)))).isEmpty();
        }

        @Test
        void should_emit_no_finding_at_the_count_boundary() {
            // The rule fires only when count > MIN_BULKHEAD_COUNT_THRESHOLD (strictly greater),
            // so 5 bulkheads at the threshold should not produce a finding even when the
            // aggregate sails over the permit threshold. Pinned to lock the boundary semantics
            // — adding a sixth bulkhead is the visible difference between "fine" and "warn".

            MultipleBulkheadsNoAggregateLimitRule rule =
                    new MultipleBulkheadsNoAggregateLimitRule();

            assertThat(rule.check(viewOf(generate(5, 50)))).isEmpty();
        }

        @Test
        void should_emit_no_finding_at_the_permit_boundary() {
            // 6 bulkheads × 16 permits each = 96 aggregate. Strictly less than 100 — no
            // finding, even though the count threshold is exceeded.

            MultipleBulkheadsNoAggregateLimitRule rule =
                    new MultipleBulkheadsNoAggregateLimitRule();

            assertThat(rule.check(viewOf(generate(6, 16)))).isEmpty();
        }

        @Test
        void should_emit_no_finding_for_an_empty_runtime() {
            MultipleBulkheadsNoAggregateLimitRule rule =
                    new MultipleBulkheadsNoAggregateLimitRule();

            assertThat(rule.check(viewOf())).isEmpty();
        }
    }

    @Nested
    @DisplayName("metadata")
    class Metadata {

        @Test
        void should_carry_a_stable_rule_id_and_default_severity() {
            MultipleBulkheadsNoAggregateLimitRule rule =
                    new MultipleBulkheadsNoAggregateLimitRule();

            assertThat(rule.ruleId())
                    .isEqualTo("MULTIPLE_BULKHEADS_NO_AGGREGATE_LIMIT");
            assertThat(rule.defaultSeverity()).isEqualTo(Severity.WARNING);
        }
    }

    private static BulkheadSnapshot[] generate(int count, int permits) {
        List<BulkheadSnapshot> snapshots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            snapshots.add(snap("bh-" + i, permits));
        }
        return snapshots.toArray(BulkheadSnapshot[]::new);
    }
}
