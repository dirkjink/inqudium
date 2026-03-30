package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.bulkhead.AimdLimitAlgorithm;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.InqLimitAlgorithm;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class ImperativeBulkheadFactoryTest {

  private Object extractStateMachine(ImperativeBulkhead bulkhead) throws Exception {
    Field stateMachineField = ImperativeBulkhead.class.getDeclaredField("stateMachine");
    stateMachineField.setAccessible(true);
    return stateMachineField.get(bulkhead);
  }

  @Nested
  class StaticBulkheadCreation {

    @Test
    void a_configuration_without_an_algorithm_creates_a_bulkhead_with_a_static_state_machine() throws Exception {
      // Given
      // A default configuration has no limit algorithm assigned
      BulkheadConfig config = BulkheadConfig.builder().build();

      // When
      // We create the bulkhead via the factory
      ImperativeBulkhead bulkhead = ImperativeBulkheadFactory.create("static-test", config);

      // Then
      // The internal state machine must be the semaphore-based implementation
      Object internalStateMachine = extractStateMachine(bulkhead);
      assertThat(internalStateMachine).isInstanceOf(ImperativeBulkheadStateMachine.class);
      assertThat(bulkhead.getName()).isEqualTo("static-test");
    }
  }

  // ── Helper methods ──

  @Nested
  class AdaptiveBulkheadCreation {

    @Test
    void a_configuration_with_an_algorithm_creates_a_bulkhead_with_an_adaptive_state_machine() throws Exception {
      // Given
      // A configuration with an explicitly defined limit algorithm
      InqLimitAlgorithm algorithm = new AimdLimitAlgorithm(10, 5, 20, 0.5);
      BulkheadConfig config = BulkheadConfig.builder()
          .limitAlgorithm(algorithm)
          .build();

      // When
      // We create the bulkhead via the factory
      ImperativeBulkhead bulkhead = ImperativeBulkheadFactory.create("adaptive-test", config);

      // Then
      // The internal state machine must be the dynamic, lock-based implementation
      Object internalStateMachine = extractStateMachine(bulkhead);
      assertThat(internalStateMachine).isInstanceOf(AdaptiveImperativeStateMachine.class);
      assertThat(bulkhead.getName()).isEqualTo("adaptive-test");
    }
  }
}
