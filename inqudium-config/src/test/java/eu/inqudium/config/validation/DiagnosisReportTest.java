package eu.inqudium.config.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("DiagnosisReport")
class DiagnosisReportTest {

    private static DiagnosticFinding finding(String ruleId, Severity severity) {
        return new DiagnosticFinding(ruleId, severity, Set.of(), "msg", Map.of());
    }

    @Test
    void should_default_a_null_findings_list_to_empty() {
        // Given / When
        DiagnosisReport report = new DiagnosisReport(Instant.now(), null);

        // Then
        assertThat(report.findings()).isEmpty();
        assertThat(report.hasErrors()).isFalse();
    }

    @Test
    void should_defensively_copy_the_findings_list() {
        // Given
        List<DiagnosticFinding> mutable = new ArrayList<>();
        mutable.add(finding("R1", Severity.WARNING));
        DiagnosisReport report = new DiagnosisReport(Instant.now(), mutable);

        // When
        mutable.add(finding("R2", Severity.ERROR));

        // Then
        assertThat(report.findings()).hasSize(1);
        assertThat(report.hasErrors()).isFalse();
    }

    @Test
    void should_reject_a_null_timestamp() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new DiagnosisReport(null, List.of()))
                .withMessageContaining("timestamp");
    }

    @Test
    void hasErrors_should_return_true_when_any_error_finding_is_present() {
        // Given
        DiagnosisReport report = new DiagnosisReport(
                Instant.now(),
                List.of(finding("W", Severity.WARNING),
                        finding("E", Severity.ERROR),
                        finding("I", Severity.INFO)));

        // When / Then
        assertThat(report.hasErrors()).isTrue();
    }

    @Test
    void errors_should_filter_to_just_error_findings() {
        // Given
        DiagnosisReport report = new DiagnosisReport(
                Instant.now(),
                List.of(finding("W", Severity.WARNING),
                        finding("E1", Severity.ERROR),
                        finding("E2", Severity.ERROR),
                        finding("I", Severity.INFO)));

        // When / Then
        assertThat(report.errors())
                .extracting(DiagnosticFinding::ruleId)
                .containsExactly("E1", "E2");
    }
}
