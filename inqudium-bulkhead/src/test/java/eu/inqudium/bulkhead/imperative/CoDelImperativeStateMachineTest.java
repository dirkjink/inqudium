package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.bulkhead.BulkheadConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CoDelImperativeStateMachineTest {

  @Nested
  class ControlledDelayLoadShedding {

    @Test
    void requests_are_granted_if_the_wait_time_remains_below_the_target_delay() throws Exception {
      // Given
      BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(1).build();
      // Target delay 50ms, Interval 200ms
      CoDelImperativeStateMachine stateMachine = new CoDelImperativeStateMachine(
          "codel-test", config, Duration.ofMillis(50), Duration.ofMillis(200));

      // When
      // The permit is instantly available
      boolean acquired = stateMachine.tryAcquireBlocking("call-1", Duration.ofSeconds(1));

      // Then
      // No wait time occurred, so it must be granted
      assertThat(acquired).isTrue();
    }

    @Test
    void requests_are_rejected_if_the_wait_time_exceeds_the_target_for_longer_than_the_interval() throws Exception {
      // Given
      BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(1).build();
      // Target delay 10ms, Interval 50ms
      CoDelImperativeStateMachine stateMachine = new CoDelImperativeStateMachine(
          "codel-test", config, Duration.ofMillis(10), Duration.ofMillis(50));

      // 1. We exhaust the capacity immediately.
      stateMachine.tryAcquireNonBlocking("call-1");

      // 2. We trigger a background thread to release call-1 after 30ms.
      // This will force call-2 to wait 30ms (> 10ms target delay).
      new Thread(() -> {
        try {
          Thread.sleep(30);
          stateMachine.releaseAndReport("call-1", Duration.ofMillis(30), null);
        } catch (InterruptedException ignored) {}
      }).start();

      // call-2 waits ~30ms. CoDel detects the delay and STARTS the 50ms interval clock,
      // but it correctly grants the permit to allow a temporary burst.
      boolean acquired2 = stateMachine.tryAcquireBlocking("call-2", Duration.ofSeconds(1));
      assertThat(acquired2).isTrue();

      // 3. We let the 50ms interval expire. Since call-2 is currently holding
      // the only permit, the queue remains continuously backed up.
      Thread.sleep(60);

      // 4. We start another background thread to release call-2 after 30ms.
      // This forces the next request (call-3) to also wait 30ms (> 10ms target).
      new Thread(() -> {
        try {
          Thread.sleep(30);
          stateMachine.releaseAndReport("call-2", Duration.ofMillis(90), null);
        } catch (InterruptedException ignored) {}
      }).start();

      // 5. call-3 waits ~30ms. Now the wait time is > 10ms AND the 50ms interval
      // has expired since call-2 started the timer. CoDel enters the dropping state!
      boolean acquired3 = stateMachine.tryAcquireBlocking("call-3", Duration.ofSeconds(1));

      // Then
      // The request must be actively rejected to shed load and drain the queue.
      assertThat(acquired3).isFalse();
    }
  }
}
