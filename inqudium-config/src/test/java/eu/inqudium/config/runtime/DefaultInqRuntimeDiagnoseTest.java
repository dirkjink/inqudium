package eu.inqudium.config.runtime;

import eu.inqudium.config.dsl.GeneralSnapshotBuilder;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.spi.ParadigmProvider;
import eu.inqudium.config.validation.BuildReport;
import eu.inqudium.config.validation.CrossComponentRule;
import eu.inqudium.config.validation.DiagnosisReport;
import eu.inqudium.config.validation.DiagnosticFinding;
import eu.inqudium.config.validation.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultInqRuntime.diagnose mechanics")
class DefaultInqRuntimeDiagnoseTest {

    private static DefaultInqRuntime runtimeWith(List<CrossComponentRule> rules) {
        GeneralSnapshot general = new GeneralSnapshotBuilder().build();
        BuildReport empty = new BuildReport(Instant.now(), List.of(), List.of(), Map.of());
        Map<ParadigmTag, ParadigmContainer<?>> containers = Map.of();
        Map<ParadigmTag, ParadigmProvider> providers = Map.of();
        return new DefaultInqRuntime(general, containers, providers, empty, rules);
    }

    @Nested
    @DisplayName("registry shape")
    class RegistryShape {

        @Test
        void should_return_empty_findings_when_no_rules_are_registered() {
            // What is to be tested: diagnose() against a runtime with no rules returns a report
            // whose findings list is empty. Why important: the no-rules case is the
            // "library on a classpath without any class-4 rule providers" baseline — it must
            // not throw, must not return null, must not invent findings.

            try (DefaultInqRuntime runtime = runtimeWith(List.of())) {
                // When
                DiagnosisReport report = runtime.diagnose();

                // Then
                assertThat(report.findings()).isEmpty();
                assertThat(report.timestamp()).isNotNull();
                assertThat(report.hasErrors()).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("rule iteration")
    class RuleIteration {

        @Test
        void should_collect_findings_emitted_by_a_rule() {
            // Given
            DiagnosticFinding canned = new DiagnosticFinding(
                    "TEST_RULE", Severity.WARNING, Set.of(), "the message", Map.of());
            CrossComponentRule rule = new StaticFindingRule("TEST_RULE", canned);

            try (DefaultInqRuntime runtime = runtimeWith(List.of(rule))) {
                // When
                DiagnosisReport report = runtime.diagnose();

                // Then
                assertThat(report.findings()).containsExactly(canned);
            }
        }

        @Test
        void should_return_no_findings_when_rule_returns_empty_list() {
            CrossComponentRule rule = new EmptyRule("QUIET_RULE");

            try (DefaultInqRuntime runtime = runtimeWith(List.of(rule))) {
                DiagnosisReport report = runtime.diagnose();

                assertThat(report.findings()).isEmpty();
            }
        }

        @Test
        void should_iterate_rules_in_registration_order() {
            // What is to be tested: the report's findings appear in rule-iteration order so
            // tooling can rely on a deterministic list.

            DiagnosticFinding fA = new DiagnosticFinding(
                    "A", Severity.INFO, Set.of(), "a", Map.of());
            DiagnosticFinding fB = new DiagnosticFinding(
                    "B", Severity.INFO, Set.of(), "b", Map.of());
            CrossComponentRule a = new StaticFindingRule("A", fA);
            CrossComponentRule b = new StaticFindingRule("B", fB);

            try (DefaultInqRuntime runtime = runtimeWith(List.of(a, b))) {
                DiagnosisReport report = runtime.diagnose();

                assertThat(report.findings()).containsExactly(fA, fB);
            }
        }

        @Test
        void should_tolerate_a_rule_that_returns_a_null_findings_list() {
            // What is to be tested: a misbehaving rule that returns null is treated as
            // "no findings" rather than crashing diagnose. Why: the contract says non-null, but
            // diagnose is meant to be robust against rule defects — a null return is morally
            // equivalent to an empty list.
            CrossComponentRule rule = new CrossComponentRule() {
                @Override public String ruleId() { return "NULL_RETURNING"; }
                @Override public Severity defaultSeverity() { return Severity.INFO; }
                @Override public List<DiagnosticFinding> check(InqConfigView view) {
                    return null;
                }
            };

            try (DefaultInqRuntime runtime = runtimeWith(List.of(rule))) {
                DiagnosisReport report = runtime.diagnose();

                assertThat(report.findings()).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {

        @Test
        void should_emit_a_rule_failure_finding_when_a_rule_throws() {
            // What is to be tested: a rule whose check(...) throws does not abort the diagnose
            // call — the runtime emits a synthetic INQ-DIAGNOSE-RULE-FAILURE finding carrying
            // the offending rule's id and exception class, and continues with the remaining
            // rules.
            // Why important: a single misbehaving rule must not blind operational tooling to
            // every other rule's findings; that is the whole point of having an isolated
            // failure-reporting path rather than letting the exception propagate.

            DiagnosticFinding goodFinding = new DiagnosticFinding(
                    "GOOD", Severity.INFO, Set.of(), "good", Map.of());
            CrossComponentRule throwing = new ThrowingRule("BAD",
                    new RuntimeException("kaboom"));
            CrossComponentRule good = new StaticFindingRule("GOOD", goodFinding);

            try (DefaultInqRuntime runtime = runtimeWith(List.of(throwing, good))) {
                // When
                DiagnosisReport report = runtime.diagnose();

                // Then
                assertThat(report.findings()).hasSize(2);
                DiagnosticFinding failure = report.findings().get(0);
                assertThat(failure.ruleId())
                        .isEqualTo(DefaultInqRuntime.DIAGNOSE_RULE_FAILURE_RULE_ID);
                assertThat(failure.severity()).isEqualTo(Severity.ERROR);
                assertThat(failure.message()).contains("BAD").contains("kaboom");
                assertThat(failure.context()).containsEntry("ruleId", "BAD");
                assertThat(failure.context())
                        .containsEntry("exceptionClass", RuntimeException.class.getName());

                assertThat(report.findings().get(1))
                        .as("the second rule still ran despite the first one's failure")
                        .isEqualTo(goodFinding);
            }
        }

        @Test
        void should_attribute_a_rule_failure_to_the_classname_when_ruleId_also_throws() {
            // What is to be tested: even if the offending rule's ruleId() accessor itself
            // throws, the synthetic finding still identifies the rule by its class name so the
            // operator can locate it. Why: a rule whose check explodes is bad enough; one whose
            // identity also explodes should not fall off the radar entirely.

            CrossComponentRule rule = new CrossComponentRule() {
                @Override public String ruleId() { throw new RuntimeException("id-failed"); }
                @Override public Severity defaultSeverity() { return Severity.WARNING; }
                @Override public List<DiagnosticFinding> check(InqConfigView view) {
                    throw new RuntimeException("check-failed");
                }
            };

            try (DefaultInqRuntime runtime = runtimeWith(List.of(rule))) {
                DiagnosisReport report = runtime.diagnose();

                assertThat(report.findings()).hasSize(1);
                DiagnosticFinding finding = report.findings().get(0);
                assertThat(finding.ruleId())
                        .isEqualTo(DefaultInqRuntime.DIAGNOSE_RULE_FAILURE_RULE_ID);
                assertThat(finding.context().get("ruleId"))
                        .as("falls back to the rule's class name when ruleId() throws")
                        .isEqualTo(rule.getClass().getName());
            }
        }

        @Test
        void should_run_diagnose_repeatedly_without_drift() {
            // What is to be tested: calling diagnose multiple times on the same runtime produces
            // identical findings every time. Confirms the rule iteration is stateless from the
            // runtime's side — no implicit "remember the last finding" behaviour creeps in.

            AtomicInteger calls = new AtomicInteger();
            DiagnosticFinding finding = new DiagnosticFinding(
                    "STABLE", Severity.INFO, Set.of(), "stable", Map.of());
            CrossComponentRule rule = new CrossComponentRule() {
                @Override public String ruleId() { return "STABLE"; }
                @Override public Severity defaultSeverity() { return Severity.INFO; }
                @Override public List<DiagnosticFinding> check(InqConfigView view) {
                    calls.incrementAndGet();
                    return List.of(finding);
                }
            };

            try (DefaultInqRuntime runtime = runtimeWith(List.of(rule))) {
                DiagnosisReport first = runtime.diagnose();
                DiagnosisReport second = runtime.diagnose();

                assertThat(first.findings()).isEqualTo(second.findings());
                assertThat(calls.get())
                        .as("the rule was invoked once per diagnose call")
                        .isEqualTo(2);
            }
        }
    }

    @Nested
    @DisplayName("read-only contract")
    class ReadOnly {

        @Test
        void should_pass_the_runtime_config_view_unchanged_to_each_rule() {
            // What is to be tested: every rule receives the exact same InqConfigView reference
            // — diagnose does not wrap or copy the view, and the view is the runtime's own.
            // Why important: a rule that holds the reference between calls must see fresh
            // snapshots on subsequent calls; if diagnose handed out a defensive copy frozen at
            // diagnose-call time, that contract would silently break in phase 3 when rules
            // grow more sophisticated.

            CapturingRule capture = new CapturingRule();
            try (DefaultInqRuntime runtime = runtimeWith(List.of(capture))) {
                runtime.diagnose();

                assertThat(capture.captured)
                        .as("rules see the runtime's live config view")
                        .isSameAs(runtime.config());
            }
        }
    }

    private static final class StaticFindingRule implements CrossComponentRule {
        private final String id;
        private final DiagnosticFinding finding;

        StaticFindingRule(String id, DiagnosticFinding finding) {
            this.id = id;
            this.finding = finding;
        }

        @Override public String ruleId() { return id; }
        @Override public Severity defaultSeverity() { return finding.severity(); }
        @Override public List<DiagnosticFinding> check(InqConfigView view) {
            return List.of(finding);
        }
    }

    private static final class EmptyRule implements CrossComponentRule {
        private final String id;

        EmptyRule(String id) { this.id = id; }

        @Override public String ruleId() { return id; }
        @Override public Severity defaultSeverity() { return Severity.INFO; }
        @Override public List<DiagnosticFinding> check(InqConfigView view) {
            return List.of();
        }
    }

    private static final class ThrowingRule implements CrossComponentRule {
        private final String id;
        private final RuntimeException toThrow;

        ThrowingRule(String id, RuntimeException toThrow) {
            this.id = id;
            this.toThrow = toThrow;
        }

        @Override public String ruleId() { return id; }
        @Override public Severity defaultSeverity() { return Severity.WARNING; }
        @Override public List<DiagnosticFinding> check(InqConfigView view) {
            throw toThrow;
        }
    }

    private static final class CapturingRule implements CrossComponentRule {
        InqConfigView captured;

        @Override public String ruleId() { return "CAPTURING"; }
        @Override public Severity defaultSeverity() { return Severity.INFO; }
        @Override public List<DiagnosticFinding> check(InqConfigView view) {
            this.captured = view;
            return List.of();
        }
    }

}
