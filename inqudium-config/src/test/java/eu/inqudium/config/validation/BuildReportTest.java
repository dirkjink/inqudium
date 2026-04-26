package eu.inqudium.config.validation;

import eu.inqudium.config.runtime.ComponentKey;
import eu.inqudium.config.runtime.ImperativeTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("BuildReport")
class BuildReportTest {

    private static ValidationFinding error(String ruleId) {
        return new ValidationFinding(ruleId, Severity.ERROR, "c", "m");
    }

    private static ValidationFinding warning(String ruleId) {
        return new ValidationFinding(ruleId, Severity.WARNING, "c", "m");
    }

    private static ValidationFinding info(String ruleId) {
        return new ValidationFinding(ruleId, Severity.INFO, "c", "m");
    }

    @Nested
    @DisplayName("compact constructor")
    class CompactConstructor {

        @Test
        void should_default_null_collections_to_empty_immutables() {
            // Given / When
            BuildReport report = new BuildReport(Instant.now(), null, null, null);

            // Then
            assertThat(report.findings()).isEmpty();
            assertThat(report.vetoFindings()).isEmpty();
            assertThat(report.componentOutcomes()).isEmpty();
        }

        @Test
        void should_defensively_copy_findings_and_outcomes() {
            // Given
            List<ValidationFinding> mutableFindings = new ArrayList<>();
            mutableFindings.add(warning("R"));
            ComponentKey keyC = new ComponentKey("c", ImperativeTag.INSTANCE);
            ComponentKey keyC2 = new ComponentKey("c2", ImperativeTag.INSTANCE);
            Map<ComponentKey, ApplyOutcome> mutableOutcomes = new HashMap<>();
            mutableOutcomes.put(keyC, ApplyOutcome.PATCHED);

            BuildReport report = new BuildReport(
                    Instant.now(), mutableFindings, List.of(), mutableOutcomes);

            // When
            mutableFindings.add(error("LATER"));
            mutableOutcomes.put(keyC2, ApplyOutcome.ADDED);

            // Then
            assertThat(report.findings()).hasSize(1);
            assertThat(report.componentOutcomes())
                    .containsOnly(Map.entry(keyC, ApplyOutcome.PATCHED));
        }

        @Test
        void should_reject_a_null_timestamp() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new BuildReport(null, List.of(), List.of(), Map.of()))
                    .withMessageContaining("timestamp");
        }
    }

    @Nested
    @DisplayName("isSuccess")
    class IsSuccess {

        @Test
        void should_be_true_for_an_empty_report() {
            // Given / When
            BuildReport report = new BuildReport(Instant.now(), List.of(), List.of(), Map.of());

            // Then
            assertThat(report.isSuccess()).isTrue();
        }

        @Test
        void should_be_true_when_only_warnings_or_info_are_present() {
            // Given / When
            BuildReport report = new BuildReport(
                    Instant.now(),
                    List.of(warning("W"), info("I")),
                    List.of(),
                    Map.of());

            // Then
            assertThat(report.isSuccess()).isTrue();
        }

        @Test
        void should_be_false_when_any_error_is_present() {
            // Given / When
            BuildReport report = new BuildReport(
                    Instant.now(),
                    List.of(warning("W"), error("E"), info("I")),
                    List.of(),
                    Map.of());

            // Then
            assertThat(report.isSuccess()).isFalse();
        }

        @Test
        void should_remain_true_when_only_veto_findings_are_present() {
            // What is to be tested: that a vetoed patch does not flip isSuccess to false. Why:
            // a veto is a policy outcome, not a validation failure — the runtime continues, the
            // affected component just keeps its prior snapshot. ADR-028 explicitly distinguishes
            // VETOED from REJECTED on this point.
            // Why important: dashboards conflating the two would over-count "broken builds" and
            // mask real validation errors behind ordinary policy rejections.

            // Given
            VetoFinding veto = new VetoFinding(
                    "c",
                    java.util.Set.of(),
                    "policy",
                    VetoFinding.Source.LISTENER);

            // When
            BuildReport report = new BuildReport(
                    Instant.now(), List.of(), List.of(veto), Map.of());

            // Then
            assertThat(report.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("warnings and errors streams")
    class FilteredStreams {

        @Test
        void should_filter_warnings() {
            // Given
            BuildReport report = new BuildReport(
                    Instant.now(),
                    List.of(warning("W1"), error("E"), warning("W2"), info("I")),
                    List.of(),
                    Map.of());

            // When / Then
            assertThat(report.warnings())
                    .extracting(ValidationFinding::ruleId)
                    .containsExactly("W1", "W2");
        }

        @Test
        void should_filter_errors() {
            // Given
            BuildReport report = new BuildReport(
                    Instant.now(),
                    List.of(warning("W"), error("E1"), error("E2"), info("I")),
                    List.of(),
                    Map.of());

            // When / Then
            assertThat(report.errors())
                    .extracting(ValidationFinding::ruleId)
                    .containsExactly("E1", "E2");
        }
    }
}
