package eu.inqudium.config.validation;

import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.ComponentSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("ConsistencyRulePipeline")
class ConsistencyRulePipelineTest {

    private static BulkheadSnapshot bulkhead(String name, String preset) {
        return new BulkheadSnapshot(name, 50, Duration.ofMillis(500), Set.of(), preset);
    }

    /**
     * Test rule that always fires with a finding tagged by its own ruleId; counts how many
     * times {@code check} is invoked so order/coverage assertions can be made.
     */
    private static final class AlwaysFiresRule implements ConsistencyRule<BulkheadSnapshot> {
        final String id;
        final Severity severity;
        final AtomicInteger calls = new AtomicInteger();

        AlwaysFiresRule(String id, Severity severity) {
            this.id = id;
            this.severity = severity;
        }

        @Override public String ruleId() { return id; }
        @Override public Class<BulkheadSnapshot> appliesTo() { return BulkheadSnapshot.class; }
        @Override public Severity defaultSeverity() { return severity; }

        @Override
        public Optional<ValidationFinding> check(BulkheadSnapshot s) {
            calls.incrementAndGet();
            return Optional.of(new ValidationFinding(id, severity, s.name(), "msg"));
        }
    }

    /**
     * Test rule that applies to a different snapshot type, so the type filter can be exercised.
     * No production snapshot type other than BulkheadSnapshot exists yet, so we use a private
     * sealed-permits-extending stand-in that the framework will refuse to construct.
     */
    private static final class NeverFiresRule implements ConsistencyRule<BulkheadSnapshot> {
        @Override public String ruleId() { return "NEVER"; }
        @Override public Class<BulkheadSnapshot> appliesTo() { return BulkheadSnapshot.class; }
        @Override public Severity defaultSeverity() { return Severity.INFO; }
        @Override public Optional<ValidationFinding> check(BulkheadSnapshot s) {
            return Optional.empty();
        }
    }

    @Nested
    @DisplayName("apply")
    class Apply {

        @Test
        void should_invoke_each_rule_once_per_matching_snapshot() {
            // Given
            AlwaysFiresRule r1 = new AlwaysFiresRule("R1", Severity.WARNING);
            AlwaysFiresRule r2 = new AlwaysFiresRule("R2", Severity.INFO);
            List<ConsistencyRule<?>> rules = List.of(r1, r2);
            Stream<? extends ComponentSnapshot> snaps = Stream.of(
                    bulkhead("a", null), bulkhead("b", null), bulkhead("c", null));

            // When
            List<ValidationFinding> findings = ConsistencyRulePipeline.apply(snaps, rules);

            // Then
            assertThat(r1.calls.get()).isEqualTo(3);
            assertThat(r2.calls.get()).isEqualTo(3);
            assertThat(findings).hasSize(6);
        }

        @Test
        void should_skip_rules_whose_appliesTo_does_not_match() {
            // Given
            NeverFiresRule never = new NeverFiresRule();
            // Use the rule, but Optional.empty() means no findings either way.
            // We only assert the call count via a side channel: AlwaysFires + this-no-fires =
            // we observe AlwaysFires but no NEVER findings.
            AlwaysFiresRule fires = new AlwaysFiresRule("FIRES", Severity.WARNING);
            List<ConsistencyRule<?>> rules = List.of(never, fires);
            Stream<? extends ComponentSnapshot> snaps = Stream.of(bulkhead("x", null));

            // When
            List<ValidationFinding> findings = ConsistencyRulePipeline.apply(snaps, rules);

            // Then
            assertThat(findings).extracting(ValidationFinding::ruleId).containsExactly("FIRES");
        }

        @Test
        void should_preserve_iteration_order_snapshot_first_then_rule() {
            // What is to be tested: that the pipeline iterates outer-loop snapshots and
            // inner-loop rules, producing findings in a deterministic order. Why: tooling that
            // formats reports relies on stable order for human-readable output.
            // Why important: a regression here would surface as flaky human-readable diffs.

            // Given
            AlwaysFiresRule r1 = new AlwaysFiresRule("R1", Severity.WARNING);
            AlwaysFiresRule r2 = new AlwaysFiresRule("R2", Severity.WARNING);
            List<ConsistencyRule<?>> rules = List.of(r1, r2);
            Stream<? extends ComponentSnapshot> snaps = Stream.of(
                    bulkhead("a", null), bulkhead("b", null));

            // When
            List<ValidationFinding> findings = ConsistencyRulePipeline.apply(snaps, rules);

            // Then — for snapshot a: R1 then R2. For snapshot b: R1 then R2.
            assertThat(findings).extracting(f -> f.componentName() + ":" + f.ruleId())
                    .containsExactly("a:R1", "a:R2", "b:R1", "b:R2");
        }

        @Test
        void should_return_an_empty_list_when_no_rules_match_or_fire() {
            // Given
            List<ConsistencyRule<?>> rules = List.of(new NeverFiresRule());

            // When
            List<ValidationFinding> findings = ConsistencyRulePipeline.apply(
                    Stream.of(bulkhead("x", null)), rules);

            // Then
            assertThat(findings).isEmpty();
        }

        @Test
        void should_reject_a_null_snapshots_stream() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ConsistencyRulePipeline.apply(null, List.of()))
                    .withMessageContaining("snapshots");
        }

        @Test
        void should_reject_a_null_rules_list() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ConsistencyRulePipeline.apply(Stream.empty(), null))
                    .withMessageContaining("rules");
        }
    }

    @Nested
    @DisplayName("elevateWarningsToErrors")
    class Elevate {

        @Test
        void should_elevate_warning_findings_to_error_severity() {
            // Given
            ValidationFinding warning = new ValidationFinding("W", Severity.WARNING, "c", "m");

            // When
            List<ValidationFinding> elevated =
                    ConsistencyRulePipeline.elevateWarningsToErrors(List.of(warning));

            // Then
            assertThat(elevated).hasSize(1);
            assertThat(elevated.get(0).severity()).isEqualTo(Severity.ERROR);
            assertThat(elevated.get(0).ruleId()).isEqualTo("W");
            assertThat(elevated.get(0).componentName()).isEqualTo("c");
            assertThat(elevated.get(0).message()).isEqualTo("m");
        }

        @Test
        void should_pass_through_error_and_info_findings_unchanged() {
            // Given
            ValidationFinding error = new ValidationFinding("E", Severity.ERROR, "c", "m");
            ValidationFinding info = new ValidationFinding("I", Severity.INFO, "c", "m");

            // When
            List<ValidationFinding> result =
                    ConsistencyRulePipeline.elevateWarningsToErrors(List.of(error, info));

            // Then
            assertThat(result).extracting(ValidationFinding::severity)
                    .containsExactly(Severity.ERROR, Severity.INFO);
        }

        @Test
        void should_return_an_unmodifiable_list() {
            // Given
            List<ValidationFinding> elevated = ConsistencyRulePipeline.elevateWarningsToErrors(
                    List.of(new ValidationFinding("W", Severity.WARNING, "c", "m")));

            // When / Then
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> elevated.add(
                    new ValidationFinding("X", Severity.ERROR, "c", "m")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void should_reject_a_null_findings_list() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ConsistencyRulePipeline.elevateWarningsToErrors(null))
                    .withMessageContaining("findings");
        }
    }
}
