package eu.inqudium.core.pipeline;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link AbstractBaseWrapper#inner()} performs a type-safe
 * downcast using the reified self-type token, preventing
 * ClassCastExceptions when chains mix different wrapper subclasses.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AbstractBaseWrapperInnerTypeSafetyTest {

    // ======================== Test doubles ========================

    /**
     * Concrete wrapper subclass A — self-type bound to itself.
     */
    static class WrapperA extends AbstractBaseWrapper<Object, WrapperA> {
        protected WrapperA(String name, Object delegate) {
            super(name, delegate);
        }
    }

    /**
     * Concrete wrapper subclass B — different self-type, incompatible with A.
     */
    static class WrapperB extends AbstractBaseWrapper<Object, WrapperB> {
        protected WrapperB(String name, Object delegate) {
            super(name, delegate);
        }
    }

    // ======================== Tests ========================

    @Nested
    class When_delegate_is_the_same_wrapper_type {

        @Test
        void inner_returns_the_delegate_wrapper() {
            // Given
            Object realTarget = "target";
            WrapperA inner = new WrapperA("inner", realTarget);
            WrapperA outer = new WrapperA("outer", inner);

            // When
            WrapperA result = outer.inner();

            // Then
            assertThat(result)
                    .as("inner() should return the delegate when types match")
                    .isSameAs(inner);
        }
    }

    @Nested
    class When_delegate_is_a_different_wrapper_subclass {

        @Test
        void inner_returns_null_instead_of_throwing_ClassCastException() {
            // Given — WrapperB wraps WrapperA; the delegate IS an
            //         AbstractBaseWrapper but is NOT a WrapperB
            Object realTarget = "target";
            WrapperA innerA = new WrapperA("inner-a", realTarget);
            WrapperB outerB = new WrapperB("outer-b", innerA);

            // When
            WrapperB result = outerB.inner();

            // Then — without the selfType check, this would have been an
            //         unchecked cast (WrapperA → WrapperB) that silently
            //         succeeds due to erasure, then explodes later
            assertThat(result)
                    .as("inner() should return null for incompatible wrapper types")
                    .isNull();
        }

        @Test
        void the_reverse_direction_also_returns_null() {
            // Given — WrapperA wraps WrapperB
            Object realTarget = "target";
            WrapperB innerB = new WrapperB("inner-b", realTarget);
            WrapperA outerA = new WrapperA("outer-a", innerB);

            // When
            WrapperA result = outerA.inner();

            // Then
            assertThat(result).isNull();
        }
    }

    @Nested
    class When_delegate_is_not_a_wrapper_at_all {

        @Test
        void inner_returns_null() {
            // Given
            WrapperA wrapper = new WrapperA("layer", "plain string target");

            // When
            WrapperA result = wrapper.inner();

            // Then
            assertThat(result).isNull();
        }
    }

    @Nested
    class When_three_layers_of_the_same_type_are_chained {

        @Test
        void inner_traverses_the_full_chain() {
            // Given
            Object realTarget = "target";
            WrapperA layer1 = new WrapperA("layer-1", realTarget);
            WrapperA layer2 = new WrapperA("layer-2", layer1);
            WrapperA layer3 = new WrapperA("layer-3", layer2);

            // When / Then
            assertThat(layer3.inner()).isSameAs(layer2);
            assertThat(layer3.inner().inner()).isSameAs(layer1);
            assertThat(layer3.inner().inner().inner())
                    .as("Innermost wrapper's delegate is the real target, not a wrapper")
                    .isNull();
        }
    }

    @Nested
    class When_a_homogeneous_chain_has_a_foreign_wrapper_in_the_middle {

        @Test
        void inner_stops_at_the_incompatible_layer() {
            // Given — A wraps B wraps A; the middle layer breaks the A-chain
            Object realTarget = "target";
            WrapperA deepA = new WrapperA("deep-a", realTarget);
            WrapperB middleB = new WrapperB("middle-b", deepA);
            WrapperA outerA = new WrapperA("outer-a", middleB);

            // When
            WrapperA result = outerA.inner();

            // Then — middleB is not a WrapperA, so inner() returns null
            assertThat(result)
                    .as("Chain traversal stops at the incompatible WrapperB layer")
                    .isNull();
        }
    }

    @Nested
    class Chain_metadata_is_still_shared_across_mixed_types {

        @Test
        void chainId_is_inherited_regardless_of_wrapper_subclass() {
            // Given
            Object realTarget = "target";
            WrapperA inner = new WrapperA("inner", realTarget);
            WrapperB outer = new WrapperB("outer", inner);

            // When / Then — chain identity is shared even though inner() returns null
            assertThat(outer.chainId())
                    .as("Chain ID should be inherited from the inner wrapper")
                    .isEqualTo(inner.chainId());
            assertThat(outer.inner())
                    .as("inner() returns null because the types are incompatible")
                    .isNull();
        }
    }
}
