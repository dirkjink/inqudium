package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.InqClock;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.InqBulkheadInterruptedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImperativeBulkheadStateMachineTest {

  private BulkheadConfig config;
  private ImperativeBulkheadStateMachine stateMachine;

  @BeforeEach
  void setUp() {
    // Given
    // Adapt this to your actual constructor or builder for the final BulkheadConfig class
    config = BulkheadConfig.builder()
        .maxConcurrentCalls(2)
        .nanoTimeSource(System::nanoTime)
        .clock(new StubClock())
        .build();

    stateMachine = new ImperativeBulkheadStateMachine("test-imperative", config);
  }

  @Nested
  class PermitAcquisition {

    @Test
    void should_acquire_permit_successfully_when_capacity_is_available_without_timeout() throws InterruptedException {
      // Given
      String callId = "call-1";
      Duration timeout = Duration.ZERO;

      // When
      boolean acquired = stateMachine.tryAcquire(callId, timeout);

      // Then
      assertThat(acquired).isTrue();
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(1);
      assertThat(stateMachine.getAvailablePermits()).isEqualTo(1);
    }

    @Test
    void should_acquire_permit_successfully_with_timeout() throws InterruptedException {
      // Given
      String callId = "call-1";
      Duration timeout = Duration.ofMillis(100);

      // When
      boolean acquired = stateMachine.tryAcquire(callId, timeout);

      // Then
      assertThat(acquired).isTrue();
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(1);
    }

    @Test
    void should_return_false_when_timeout_expires_and_no_capacity_is_available() throws Exception {
      // Given
      // Consume all 2 available permits
      stateMachine.tryAcquire("call-1", Duration.ZERO);
      stateMachine.tryAcquire("call-2", Duration.ZERO);

      // When
      // Attempt to acquire a 3rd permit with a short timeout.
      // This should block for 50ms and then return false.
      boolean acquired = stateMachine.tryAcquire("call-3", Duration.ofMillis(50));

      // Then
      assertThat(acquired).isFalse();
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(2);
      assertThat(stateMachine.getAvailablePermits()).isEqualTo(0);
    }
  }

  @Nested
  class ReleaseAndRollback {

    @Test
    void should_release_acquired_permit_and_make_it_available_again() throws InterruptedException {
      // Given
      stateMachine.tryAcquire("call-1", Duration.ZERO);
      assertThat(stateMachine.getAvailablePermits()).isEqualTo(1);

      // When
      // Calling releaseAndReport triggers the internal release logic
      stateMachine.releaseAndReport("call-1", Duration.ofMillis(10), null);

      // Then
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(0);
      assertThat(stateMachine.getAvailablePermits()).isEqualTo(2);
    }

    @Test
    void should_rollback_permit_correctly() throws InterruptedException {
      // Given
      stateMachine.tryAcquire("call-1", Duration.ZERO);
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(1);

      // When
      // Simulating an internal rollback (e.g. event publishing failed in handleAcquireSuccess)
      stateMachine.rollbackPermit();

      // Then
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(0);
      assertThat(stateMachine.getAvailablePermits()).isEqualTo(2);
    }
  }

  @Nested
  class EdgeCases {

    @Test
    void should_prevent_semaphore_inflation_on_double_release() throws InterruptedException {
      // Given
      // Machine is completely empty (2 available permits, 0 active calls)
      assertThat(stateMachine.getAvailablePermits()).isEqualTo(2);

      // When
      // We simulate a bad actor releasing permits without ever acquiring them
      stateMachine.releaseAndReport("phantom-call", Duration.ofMillis(10), null);
      stateMachine.releaseAndReport("phantom-call-2", Duration.ofMillis(10), null);

      // Also explicitly try the internal rollback method
      stateMachine.rollbackPermit();

      // Then
      // The AtomicInteger guard must prevent the Semaphore from being released.
      // If it failed, available permits would be 5 instead of 2.
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(0);
      assertThat(stateMachine.getAvailablePermits()).isEqualTo(2);
    }

    @Test
    void should_allow_new_acquisitions_after_preventing_inflation() throws InterruptedException {
      // Given
      // Simulate an over-release attempt on an empty machine
      stateMachine.releaseAndReport("phantom", Duration.ofMillis(10), null);

      // When
      // We now do a legitimate acquire-release cycle
      boolean acquired1 = stateMachine.tryAcquire("valid-1", Duration.ZERO);
      boolean acquired2 = stateMachine.tryAcquire("valid-2", Duration.ZERO);

      // Then
      assertThat(acquired1).isTrue();
      assertThat(acquired2).isTrue();

      // The third should fail, proving the capacity didn't inflate
      boolean acquired3 = stateMachine.tryAcquire("invalid-3", Duration.ZERO);
      assertThat(acquired3).isFalse();
    }
  }

  @Nested
  class InterruptionHandling {

    @Test
    void should_throw_exception_when_thread_is_interrupted_while_waiting() throws Exception {
      // Given
      // Consume all permits to force the next thread to block
      stateMachine.tryAcquire("call-1", Duration.ZERO);
      stateMachine.tryAcquire("call-2", Duration.ZERO);

      Thread currentThread = Thread.currentThread();

      // Run a background thread that interrupts the main thread after a short delay
      CompletableFuture.runAsync(() -> {
        try {
          Thread.sleep(50);
          currentThread.interrupt();
        } catch (InterruptedException ignored) {}
      });

      // When & Then
      // The main thread waits for a permit, but will be interrupted
      assertThatThrownBy(() -> stateMachine.tryAcquire("call-3", Duration.ofSeconds(5)))
          .isInstanceOf(InqBulkheadInterruptedException.class);

      // Clear interrupt status to not pollute the test environment
      Thread.interrupted();

      // Ensure state is unchanged
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(2);
    }
  }

  // --- Manual Test Doubles (Stubs) ---

  private static class StubClock implements InqClock {
    @Override
    public Instant instant() {
      return Instant.now();
    }
  }
}
