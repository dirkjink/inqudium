package eu.inqudium.config.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("DiagnosticFinding")
class DiagnosticFindingTest {

    @Test
    void should_carry_every_field() {
        // Given
        Set<String> components = Set.of("inventory", "payments");
        Map<String, Object> context = Map.of("threshold", 0.05);

        // When
        DiagnosticFinding f = new DiagnosticFinding(
                "RETRY_BURST_CAN_FILL_BULKHEAD",
                Severity.WARNING,
                components,
                "retry burst can saturate bulkhead",
                context);

        // Then
        assertThat(f.ruleId()).isEqualTo("RETRY_BURST_CAN_FILL_BULKHEAD");
        assertThat(f.severity()).isEqualTo(Severity.WARNING);
        assertThat(f.affectedComponents()).containsExactlyInAnyOrder("inventory", "payments");
        assertThat(f.message()).isEqualTo("retry burst can saturate bulkhead");
        assertThat(f.context()).containsEntry("threshold", 0.05);
    }

    @Test
    void should_default_a_null_components_set_to_empty() {
        // Given / When
        DiagnosticFinding f = new DiagnosticFinding(
                "R", Severity.INFO, null, "m", Map.of());

        // Then
        assertThat(f.affectedComponents()).isEmpty();
    }

    @Test
    void should_default_a_null_context_map_to_empty() {
        // Given / When
        DiagnosticFinding f = new DiagnosticFinding(
                "R", Severity.INFO, Set.of(), "m", null);

        // Then
        assertThat(f.context()).isEmpty();
    }

    @Test
    void should_defensively_copy_components_and_context() {
        // Given
        Set<String> mutableComponents = new HashSet<>();
        mutableComponents.add("a");
        Map<String, Object> mutableContext = new HashMap<>();
        mutableContext.put("k", "v");

        DiagnosticFinding f = new DiagnosticFinding(
                "R", Severity.INFO, mutableComponents, "m", mutableContext);

        // When
        mutableComponents.add("b");
        mutableContext.put("k2", "v2");

        // Then
        assertThat(f.affectedComponents()).containsExactly("a");
        assertThat(f.context()).containsOnly(Map.entry("k", "v"));
    }

    @Test
    void should_reject_a_null_rule_id() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new DiagnosticFinding(
                        null, Severity.INFO, Set.of(), "m", Map.of()))
                .withMessageContaining("ruleId");
    }

    @Test
    void should_reject_a_null_severity() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new DiagnosticFinding(
                        "R", null, Set.of(), "m", Map.of()))
                .withMessageContaining("severity");
    }

    @Test
    void should_reject_a_null_message() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new DiagnosticFinding(
                        "R", Severity.INFO, Set.of(), null, Map.of()))
                .withMessageContaining("message");
    }
}
