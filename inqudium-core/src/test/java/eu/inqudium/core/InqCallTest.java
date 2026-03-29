package eu.inqudium.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InqCall")
class InqCallTest {

    @Nested
    @DisplayName("Creation")
    class Creation {

        @Test
        void should_carry_call_id_and_supplier() {
            // Given
            var call = InqCall.of("call-1", () -> "hello");

            // Then
            assertThat(call.callId()).isEqualTo("call-1");
            assertThat(call.execute()).isEqualTo("hello");
        }

        @Test
        void should_reject_null_call_id() {
            assertThatThrownBy(() -> InqCall.of(null, () -> "x"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void should_reject_null_supplier() {
            assertThatThrownBy(() -> InqCall.of("id", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("withSupplier — decoration pattern")
    class WithSupplier {

        @Test
        void should_preserve_call_id_when_replacing_supplier() {
            // Given
            var original = InqCall.of("call-42", () -> "original");

            // When
            var decorated = original.withSupplier(() -> "decorated-" + original.supplier().get());

            // Then — same callId, different supplier
            assertThat(decorated.callId()).isEqualTo("call-42");
            assertThat(decorated.execute()).isEqualTo("decorated-original");
        }

        @Test
        void should_support_chaining_multiple_decorations() {
            // Given
            var call = InqCall.of("call-1", () -> "base");

            // When — three decoration layers
            var layer1 = call.withSupplier(() -> "[1:" + call.supplier().get() + "]");
            var layer2 = layer1.withSupplier(() -> "[2:" + layer1.supplier().get() + "]");
            var layer3 = layer2.withSupplier(() -> "[3:" + layer2.supplier().get() + "]");

            // Then — all layers share the same callId
            assertThat(layer3.callId()).isEqualTo("call-1");
            assertThat(layer3.execute()).isEqualTo("[3:[2:[1:base]]]");
        }
    }

    @Nested
    @DisplayName("Pipeline simulation")
    class PipelineSimulation {

        @Test
        void should_share_call_id_across_all_decorators_in_a_pipeline() {
            // Given — simulate three elements recording the callId they see
            var observedCallIds = new ArrayList<String>();

            var original = InqCall.of("pipeline-call-99", () -> "result");

            // When — each "element" reads the callId and decorates the supplier
            var afterCb = original.withSupplier(() -> {
                observedCallIds.add(original.callId());  // CB sees callId
                return original.supplier().get();
            });

            var afterRetry = afterCb.withSupplier(() -> {
                observedCallIds.add(afterCb.callId());   // Retry sees callId
                return afterCb.supplier().get();
            });

            var afterRl = afterRetry.withSupplier(() -> {
                observedCallIds.add(afterRetry.callId()); // RL sees callId
                return afterRetry.supplier().get();
            });

            // Execute the outermost
            var result = afterRl.execute();

            // Then — all three elements saw the same callId
            assertThat(result).isEqualTo("result");
            assertThat(observedCallIds).hasSize(3);
            assertThat(observedCallIds).containsOnly("pipeline-call-99");
        }

        @Test
        void should_not_leak_call_id_between_independent_calls() {
            // Given
            var call1 = InqCall.of("call-A", () -> "a");
            var call2 = InqCall.of("call-B", () -> "b");

            // Then
            assertThat(call1.callId()).isNotEqualTo(call2.callId());
        }
    }

    @Nested
    @DisplayName("Exception propagation")
    class ExceptionPropagation {

        @Test
        void should_propagate_exceptions_from_supplier() {
            // Given
            var call = InqCall.of("call-err", () -> { throw new RuntimeException("boom"); });

            // When / Then
            assertThatThrownBy(call::execute)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("boom");
        }
    }
}
