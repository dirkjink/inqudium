package eu.inqudium.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;

import static eu.inqudium.core.InqCallIdGenerator.NONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InqCall")
class InqCallTest {

  @Nested
  @DisplayName("Creation")
  class Creation {

    @Test
    void should_carry_call_id_and_callable() throws Exception {
      // Given
      var call = InqCall.of("call-1", () -> "hello");

      // Then
      assertThat(call.callId()).isEqualTo("call-1");
      assertThat(call.execute()).isEqualTo("hello");
    }

    @Test
    void should_allow_null_call_id_for_standalone_use() throws Exception {
      // Given
      var call = InqCall.standalone(() -> "standalone");

      // Then
      assertThat(call.callId()).isEqualTo(NONE);
      assertThat(call.execute()).isEqualTo("standalone");
    }

    @Test
    void should_reject_null_callable() {
      assertThatThrownBy(() -> InqCall.of("id", null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("withCallable — decoration pattern")
  class WithCallable {

    @Test
    void should_preserve_call_id_when_replacing_callable() throws Exception {
      // Given
      var original = InqCall.of("call-42", () -> "original");

      // When
      var decorated = original.withCallable(() -> "decorated-" + original.callable().call());

      // Then — same callId, different callable
      assertThat(decorated.callId()).isEqualTo("call-42");
      assertThat(decorated.execute()).isEqualTo("decorated-original");
    }

    @Test
    void should_support_chaining_multiple_decorations() throws Exception {
      // Given
      var call = InqCall.of("call-1", () -> "base");

      // When — three decoration layers
      var layer1 = call.withCallable(() -> "[1:" + call.callable().call() + "]");
      var layer2 = layer1.withCallable(() -> "[2:" + layer1.callable().call() + "]");
      var layer3 = layer2.withCallable(() -> "[3:" + layer2.callable().call() + "]");

      // Then — all layers share the same callId
      assertThat(layer3.callId()).isEqualTo("call-1");
      assertThat(layer3.execute()).isEqualTo("[3:[2:[1:base]]]");
    }
  }

  @Nested
  @DisplayName("Pipeline simulation")
  class PipelineSimulation {

    @Test
    void should_share_call_id_across_all_decorators_in_a_pipeline() throws Exception {
      // Given — simulate three elements recording the callId they see
      var observedCallIds = new ArrayList<String>();

      var original = InqCall.of("pipeline-call-99", () -> "result");

      // When — each "element" reads the callId and decorates the callable
      var afterCb = original.withCallable(() -> {
        observedCallIds.add(original.callId());
        return original.callable().call();
      });

      var afterRetry = afterCb.withCallable(() -> {
        observedCallIds.add(afterCb.callId());
        return afterCb.callable().call();
      });

      var afterRl = afterRetry.withCallable(() -> {
        observedCallIds.add(afterRetry.callId());
        return afterRetry.callable().call();
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
    void should_propagate_runtime_exceptions_from_callable() {
      // Given
      var call = InqCall.of("call-err", () -> {
        throw new RuntimeException("boom");
      });

      // When / Then
      assertThatThrownBy(call::execute)
          .isInstanceOf(RuntimeException.class)
          .hasMessage("boom");
    }

    @Test
    void should_propagate_checked_exceptions_from_callable() {
      // Given — Callable allows checked exceptions naturally
      var call = InqCall.<String>of("call-checked", () -> {
        throw new IOException("disk full");
      });

      // When / Then — checked exception propagates without wrapping
      assertThatThrownBy(call::execute)
          .isInstanceOf(IOException.class)
          .hasMessage("disk full");
    }
  }
}
