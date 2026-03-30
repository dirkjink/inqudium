package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.InqLimitAlgorithm;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveImperativeStateMachineTest {

  private static class FakeLimitAlgorithm implements InqLimitAlgorithm {
    int currentLimit;

    FakeLimitAlgorithm(int initialLimit) {
      this.currentLimit = initialLimit;
    }

    @Override
    public int getLimit() {
      return currentLimit;
    }

    @Override
    public void update(Duration rtt, boolean isSuccess) {
      // We do not auto-update in this fake; we manipulate it manually in the test
    }
  }

  // ── Helper methods and fake classes ──

  @Nested
  class DynamicCapacityAdjustments {

    @Test
    void the_state_machine_respects_the_dynamically_changing_limits_from_the_algorithm() {
      // Given
      BulkheadConfig config = BulkheadConfig.builder().build();
      FakeLimitAlgorithm fakeAlgorithm = new FakeLimitAlgorithm(2);
      AdaptiveImperativeStateMachine stateMachine = new AdaptiveImperativeStateMachine("test", config, fakeAlgorithm);

      // When / Then
      // We can acquire up to the current limit of 2
      assertThat(stateMachine.tryAcquireNonBlocking("call-1")).isTrue();
      assertThat(stateMachine.tryAcquireNonBlocking("call-2")).isTrue();
      assertThat(stateMachine.tryAcquireNonBlocking("call-3")).isFalse(); // Rejected, limit is 2

      // When the algorithm drops the limit to 1 due to a simulated failure feedback
      fakeAlgorithm.currentLimit = 1;
      stateMachine.releaseAndReport("call-1", Duration.ofMillis(100), new RuntimeException());

      // Then
      // Even though one was released, we still have 1 active call, which is the new max limit.
      // Therefore, the next acquire must be rejected.
      assertThat(stateMachine.tryAcquireNonBlocking("call-4")).isFalse();

      // When the limit is dynamically increased again
      fakeAlgorithm.currentLimit = 5;

      // Then we can acquire permits again immediately
      assertThat(stateMachine.tryAcquireNonBlocking("call-5")).isTrue();
    }
  }
}
