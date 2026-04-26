package eu.inqudium.config;

import eu.inqudium.config.validation.BuildReport;
import eu.inqudium.config.validation.Severity;
import eu.inqudium.config.validation.ValidationFinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("ConfigurationException")
class ConfigurationExceptionTest {

    private static ValidationFinding error(String ruleId, String message) {
        return new ValidationFinding(ruleId, Severity.ERROR, "c", message);
    }

    @Test
    void should_carry_the_report_and_summarise_the_first_error_in_the_message() {
        // Given
        BuildReport report = new BuildReport(
                Instant.now(),
                List.of(error("E1", "first thing"), error("E2", "second thing")),
                List.of(),
                Map.of());

        // When
        ConfigurationException ex = new ConfigurationException(report);

        // Then
        assertThat(ex.report()).isSameAs(report);
        assertThat(ex.getMessage())
                .contains("2 errors")
                .contains("E1")
                .contains("first thing")
                .contains("1 more");
    }

    @Test
    void should_use_singular_phrasing_for_a_single_error() {
        // Given
        BuildReport report = new BuildReport(
                Instant.now(),
                List.of(error("E", "only one")),
                List.of(),
                Map.of());

        // When
        ConfigurationException ex = new ConfigurationException(report);

        // Then
        assertThat(ex.getMessage()).contains("1 error: ").doesNotContain("more");
    }

    @Test
    void should_be_a_runtime_exception() {
        // Given
        BuildReport report = new BuildReport(
                Instant.now(), List.of(error("E", "x")), List.of(), Map.of());

        // When
        ConfigurationException ex = new ConfigurationException(report);

        // Then
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void should_reject_a_null_report() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new ConfigurationException(null))
                .withMessageContaining("report");
    }
}
