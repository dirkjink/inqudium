package eu.inqudium.config.validation;

import eu.inqudium.config.lifecycle.ComponentField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VetoFinding")
class VetoFindingTest {

    private enum TestField implements ComponentField {
        ALPHA, BETA
    }

    @Nested
    @DisplayName("compact constructor")
    class CompactConstructor {

        @Test
        void should_carry_every_field() {
            // Given
            Set<ComponentField> touched = Set.of(TestField.ALPHA, TestField.BETA);

            // When
            VetoFinding f = new VetoFinding(
                    "inventory", touched, "policy disallows", VetoFinding.Source.LISTENER);

            // Then
            assertThat(f.componentName()).isEqualTo("inventory");
            assertThat(f.touchedFields())
                    .extracting(ComponentField::name)
                    .containsExactlyInAnyOrder("ALPHA", "BETA");
            assertThat(f.reason()).isEqualTo("policy disallows");
            assertThat(f.source()).isEqualTo(VetoFinding.Source.LISTENER);
        }

        @Test
        void should_default_a_null_touched_fields_set_to_empty() {
            // Given / When
            VetoFinding f = new VetoFinding(
                    "c", null, "r", VetoFinding.Source.COMPONENT_INTERNAL);

            // Then
            assertThat(f.touchedFields()).isEmpty();
        }

        @Test
        void should_defensively_copy_the_touched_fields_set() {
            // What is to be tested: that mutations to the caller's set after construction do not
            // leak into the VetoFinding. Why: VetoFinding is shared across threads via
            // BuildReport; an aliased set would corrupt downstream observers.
            // Why important: the immutability invariant is the basis of the report's thread-
            // safety guarantee.

            // Given
            Set<ComponentField> mutable = new HashSet<>();
            mutable.add(TestField.ALPHA);
            VetoFinding f = new VetoFinding(
                    "c", mutable, "r", VetoFinding.Source.LISTENER);

            // When
            mutable.add(TestField.BETA);

            // Then
            assertThat(f.touchedFields())
                    .extracting(ComponentField::name)
                    .containsExactly("ALPHA");
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void should_reject_a_null_component_name() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new VetoFinding(
                            null, Set.of(), "r", VetoFinding.Source.LISTENER))
                    .withMessageContaining("componentName");
        }

        @Test
        void should_reject_a_null_reason() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new VetoFinding(
                            "c", Set.of(), null, VetoFinding.Source.LISTENER))
                    .withMessageContaining("reason");
        }

        @Test
        void should_reject_a_blank_reason() {
            // Given / When / Then
            assertThatThrownBy(() -> new VetoFinding(
                    "c", Set.of(), "   ", VetoFinding.Source.LISTENER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reason must not be blank");
        }

        @Test
        void should_reject_a_null_source() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new VetoFinding("c", Set.of(), "r", null))
                    .withMessageContaining("source");
        }
    }
}
