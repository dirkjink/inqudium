package eu.inqudium.config.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("ValidationFinding")
class ValidationFindingTest {

    @Test
    void should_carry_every_field_through_the_compact_constructor() {
        // Given / When
        ValidationFinding f = new ValidationFinding(
                "BULKHEAD_PROTECTIVE_WITH_LONG_WAIT",
                Severity.WARNING,
                "inventory",
                "protective preset paired with non-zero wait");

        // Then
        assertThat(f.ruleId()).isEqualTo("BULKHEAD_PROTECTIVE_WITH_LONG_WAIT");
        assertThat(f.severity()).isEqualTo(Severity.WARNING);
        assertThat(f.componentName()).isEqualTo("inventory");
        assertThat(f.message()).isEqualTo("protective preset paired with non-zero wait");
    }

    @Test
    void should_allow_a_null_component_name_for_global_findings() {
        // Given / When
        ValidationFinding f = new ValidationFinding(
                "GENERAL_RULE", Severity.ERROR, null, "global message");

        // Then
        assertThat(f.componentName()).isNull();
    }

    @Test
    void should_reject_a_null_rule_id() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new ValidationFinding(null, Severity.ERROR, "c", "m"))
                .withMessageContaining("ruleId");
    }

    @Test
    void should_reject_a_null_severity() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new ValidationFinding("R", null, "c", "m"))
                .withMessageContaining("severity");
    }

    @Test
    void should_reject_a_null_message() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new ValidationFinding("R", Severity.INFO, "c", null))
                .withMessageContaining("message");
    }
}
