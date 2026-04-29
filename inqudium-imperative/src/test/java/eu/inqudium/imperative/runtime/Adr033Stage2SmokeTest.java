package eu.inqudium.imperative.runtime;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage-2 verification gate for ADR-033. Demonstrates that {@code InqBulkhead<A, R>} carries
 * concrete type parameters end-to-end: a typed instance built via {@code Inqudium.configure()}
 * decorates a {@code Function<String, Integer>} and the wrapper preserves the call's argument
 * and return shape through the bulkhead's around-advice.
 *
 * <p>Lives in a separate scratch class for the duration of the Stage-2 review so it is easy to
 * locate and remove or relocate during Stage 3, when {@code BulkheadHandle.decorateFunction(...)}
 * is expected to subsume this surface.
 */
@DisplayName("ADR-033 Stage 2 — typed InqBulkhead smoke test")
class Adr033Stage2SmokeTest {

    @Test
    @DisplayName("should construct InqBulkhead with concrete type parameters and decorate a typed function")
    void should_construct_InqBulkhead_with_concrete_type_parameters_and_decorate_a_typed_function() {
        // What is to be tested: that the lifecycle base class's new <A, R> type parameters
        // propagate into InqBulkhead and that InqDecorator's default decorateFunction reduces
        // to a typed Function<A, R> on the concrete component without unchecked casts at the
        // user's call site.
        // Why successful: the wrapped function applied to a String input produces the correct
        // Integer result, which proves both the typing (compiler accepts) and the runtime
        // contract (decorator forwards through the bulkhead's around-advice).
        // Why important: this is Stage 2's structural-closure proof — InqBulkhead is now a
        // first-class InqDecorator<A, R>, which is the precondition for the Stage 3 surface
        // consolidation and for the AspectJ integration that follows in Phase 2 step 2.18.

        // Given
        try (InqRuntime runtime = Inqudium.configure()
                .imperative(im -> im.bulkhead("typed-test", b -> b.balanced()))
                .build()) {
            @SuppressWarnings("unchecked")
            InqBulkhead<String, Integer> bulkhead =
                    (InqBulkhead<String, Integer>) runtime.imperative().bulkhead("typed-test");

            // When
            Function<String, Integer> wrapped = bulkhead.decorateFunction(Integer::parseInt);
            Integer result = wrapped.apply("42");

            // Then
            assertThat(result).isEqualTo(42);
            assertThat(bulkhead.concurrentCalls()).isZero();
        }
    }
}
