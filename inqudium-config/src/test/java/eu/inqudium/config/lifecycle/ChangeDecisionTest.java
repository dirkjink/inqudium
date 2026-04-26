package eu.inqudium.config.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChangeDecision")
class ChangeDecisionTest {

    @Nested
    @DisplayName("Accept")
    class AcceptDecision {

        @Test
        void should_return_a_singleton_instance_from_the_static_factory() {
            // Given / When
            ChangeDecision first = ChangeDecision.accept();
            ChangeDecision second = ChangeDecision.accept();

            // Then
            assertThat(first).isSameAs(second);
            assertThat(first).isInstanceOf(ChangeDecision.Accept.class);
        }
    }

    @Nested
    @DisplayName("Veto")
    class VetoDecision {

        @Test
        void should_carry_the_reason_through_the_static_factory() {
            // Given
            String reason = "maxWaitDuration below 10ms is disallowed by site policy";

            // When
            ChangeDecision decision = ChangeDecision.veto(reason);

            // Then
            assertThat(decision).isInstanceOf(ChangeDecision.Veto.class);
            assertThat(((ChangeDecision.Veto) decision).reason()).isEqualTo(reason);
        }

        @Test
        void should_reject_a_null_reason_in_the_compact_constructor() {
            // Given a null reason. We exercise the record constructor directly to confirm the
            // contract holds for any code path, not just the static factory — class 2 invariants
            // must survive deserialization, programmatic API, and reflection.
            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new ChangeDecision.Veto(null))
                    .withMessageContaining("veto reason must not be null");
        }

        @Test
        void should_reject_a_blank_reason_in_the_compact_constructor() {
            // A blank reason satisfies non-null but contributes nothing to a debugger.
            // Given / When / Then
            assertThatThrownBy(() -> new ChangeDecision.Veto("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("veto reason must not be blank");
        }

        @Test
        void should_reject_an_empty_reason_in_the_compact_constructor() {
            // Given / When / Then
            assertThatThrownBy(() -> new ChangeDecision.Veto(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("veto reason must not be blank");
        }

        @Test
        void should_reject_a_null_reason_through_the_static_factory() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ChangeDecision.veto(null));
        }
    }

    @Nested
    @DisplayName("sealing")
    class Sealing {

        @Test
        void should_permit_only_accept_and_veto_subtypes() {
            // What is to be tested: that ChangeDecision is a sealed interface with exactly two
            // permitted subtypes (Accept, Veto). Why: the dispatcher in phase 2 will rely on
            // exhaustive pattern matching on this hierarchy; an inadvertent third subtype would
            // silently bypass the pattern's exhaustiveness check and produce wrong-state bugs.
            // Why important: the sealing is a load-bearing API guarantee, not a stylistic choice.

            // Given / When
            Class<?>[] permitted = ChangeDecision.class.getPermittedSubclasses();

            // Then
            assertThat(ChangeDecision.class.isSealed()).isTrue();
            assertThat(permitted)
                    .hasSize(2)
                    .containsExactlyInAnyOrder(ChangeDecision.Accept.class, ChangeDecision.Veto.class);
        }
    }
}
