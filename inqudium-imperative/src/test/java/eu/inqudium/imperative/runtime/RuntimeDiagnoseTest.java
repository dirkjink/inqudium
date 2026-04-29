package eu.inqudium.imperative.runtime;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.validation.DiagnosisReport;
import eu.inqudium.config.validation.DiagnosticFinding;
import eu.inqudium.config.validation.Severity;
import eu.inqudium.config.validation.rules.MultipleBulkheadsNoAggregateLimitRule;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pin for {@code runtime.diagnose()} including ServiceLoader-based discovery of the
 * built-in {@link MultipleBulkheadsNoAggregateLimitRule}. Lives in {@code inqudium-imperative}
 * because the canary rule needs real bulkheads to evaluate; the dispatcher mechanics tests live
 * in {@code inqudium-config}.
 */
@DisplayName("runtime.diagnose end-to-end")
class RuntimeDiagnoseTest {

    private static final InternalExecutor<String, String> IDENTITY =
            (chainId, callId, argument) -> argument;

    @Nested
    @DisplayName("MULTIPLE_BULKHEADS_NO_AGGREGATE_LIMIT canary rule")
    class CanaryRule {

        @Test
        void should_fire_for_six_bulkheads_with_fifty_permits_each() {
            // What is to be tested: a runtime built with 6 bulkheads × 50 permits exceeds the
            // canary's count and aggregate thresholds and produces the documented warning when
            // diagnose() is invoked. Why successful: the report's findings list contains a
            // single entry whose ruleId matches MultipleBulkheadsNoAggregateLimitRule.ID.
            // Why important: confirms the production ServiceLoader entry is wired up correctly
            // — without it the runtime would silently swallow the rule, and the canary would
            // never fire in real systems either.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im
                            .bulkhead("bh-1", b -> b.balanced().maxConcurrentCalls(50))
                            .bulkhead("bh-2", b -> b.balanced().maxConcurrentCalls(50))
                            .bulkhead("bh-3", b -> b.balanced().maxConcurrentCalls(50))
                            .bulkhead("bh-4", b -> b.balanced().maxConcurrentCalls(50))
                            .bulkhead("bh-5", b -> b.balanced().maxConcurrentCalls(50))
                            .bulkhead("bh-6", b -> b.balanced().maxConcurrentCalls(50)))
                    .build()) {

                DiagnosisReport report = runtime.diagnose();

                assertThat(report.findings()).hasSize(1);
                DiagnosticFinding finding = report.findings().get(0);
                assertThat(finding.ruleId())
                        .isEqualTo(MultipleBulkheadsNoAggregateLimitRule.ID);
                assertThat(finding.severity()).isEqualTo(Severity.WARNING);
                assertThat(finding.affectedComponents())
                        .containsExactlyInAnyOrder(
                                "bh-1", "bh-2", "bh-3", "bh-4", "bh-5", "bh-6");
            }
        }

        @Test
        void should_not_fire_for_two_bulkheads_with_fifty_permits_each() {
            // 2 × 50 = 100 aggregate, count well below threshold. Nothing to warn about.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im
                            .bulkhead("alpha", b -> b.balanced().maxConcurrentCalls(50))
                            .bulkhead("beta", b -> b.balanced().maxConcurrentCalls(50)))
                    .build()) {

                DiagnosisReport report = runtime.diagnose();

                assertThat(report.findings()).isEmpty();
                assertThat(report.hasErrors()).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("read-only contract")
    class ReadOnly {

        @Test
        void should_leave_runtime_state_unchanged_across_a_diagnose_call() {
            // What is to be tested: the runtime topology before and after diagnose() is
            // bitwise-identical from the caller's vantage point — bulkhead names, snapshots,
            // permit counts. Why important: a diagnose pipeline is expected to be safe to run
            // on production systems on a schedule; any side effect would risk operator
            // surprise.

            try (InqRuntime runtime = Inqudium.configure()
                    .imperative(im -> im
                            .bulkhead("inventory", b -> b.balanced().maxConcurrentCalls(15)))
                    .build()) {

                @SuppressWarnings("unchecked")
                InqBulkhead<String, String> bh =
                        (InqBulkhead<String, String>) runtime.imperative().bulkhead("inventory");
                bh.execute(1L, 1L, "warm", IDENTITY);

                int permitsBefore = bh.availablePermits();
                int maxBefore = bh.snapshot().maxConcurrentCalls();

                runtime.diagnose();

                assertThat(bh.availablePermits())
                        .as("availablePermits is unchanged by diagnose")
                        .isEqualTo(permitsBefore);
                assertThat(bh.snapshot().maxConcurrentCalls()).isEqualTo(maxBefore);
                assertThat(runtime.imperative().bulkheadNames()).containsExactly("inventory");
                assertThat(bh.execute(2L, 2L, "still-here", IDENTITY))
                        .isEqualTo("still-here");
            }
        }
    }
}
