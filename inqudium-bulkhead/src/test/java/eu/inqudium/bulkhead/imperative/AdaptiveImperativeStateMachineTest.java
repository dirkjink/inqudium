package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.InqClock;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.InqBulkheadInterruptedException;
import eu.inqudium.core.bulkhead.InqLimitAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdaptiveImperativeStateMachineTest {

  private BulkheadConfig config;
  private FakeLimitAlgorithm limitAlgorithm;
  private AdaptiveImperativeStateMachine stateMachine;

  @BeforeEach
  void setUp() {
    // Given
    config = BulkheadConfig.builder()
        .maxConcurrentCalls(2)
        .nanoTimeSource(System::nanoTime)
        .clock(new StubClock())
        .build();

    limitAlgorithm = new FakeLimitAlgorithm(2);
    stateMachine = new AdaptiveImperativeStateMachine("test-adaptive", config, limitAlgorithm);
  }

  @Nested
  class PermitAcquisition {

    @Test
    void should_acquire_permit_successfully_when_capacity_is_available() throws InterruptedException {
      // Given
      String callId = "call-1";
      Duration timeout = Duration.ofMillis(100);

      // When
      boolean acquired = stateMachine.tryAcquire(callId, timeout);

      // Then
      assertThat(acquired).isTrue();
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(1);
      assertThat(stateMachine.getAvailablePermits()).isEqualTo(1);
    }

    @Test
    void should_return_false_when_timeout_expires_and_no_capacity_is_available() throws InterruptedException {
      // Given
      stateMachine.tryAcquire("call-1", Duration.ZERO);
      stateMachine.tryAcquire("call-2", Duration.ZERO);

      // When
      boolean acquired = stateMachine.tryAcquire("call-3", Duration.ofMillis(10));

      // Then
      assertThat(acquired).isFalse();
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(2);
    }
  }
  @Nested
  class EdgeCases {

    @Test
    void should_prevent_active_calls_from_dropping_below_zero_on_over_release() {
      // Given
      // Ensure the state machine is completely empty (0 active calls)
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(0);

      // When
      // We simulate a bug where a caller releases a permit they never acquired
      stateMachine.releaseAndReport("phantom-call", Duration.ofMillis(10), null);
      stateMachine.releaseAndReport("phantom-call-2", Duration.ofMillis(10), null);

      // Then
      // The active calls must not drop below zero, preventing artificial capacity inflation
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(0);
      assertThat(stateMachine.getAvailablePermits()).isEqualTo(config.getMaxConcurrentCalls());
    }

    @Test
    void should_release_permit_even_if_adaptive_algorithm_throws_an_exception() throws InterruptedException {
      // Given
      stateMachine.tryAcquire("call-1", Duration.ZERO);
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(1);

      // Configure the fake algorithm to crash when update() is called
      limitAlgorithm.setThrowOnUpdate(true);

      // When
      // Releasing the permit triggers the algorithm update, which will throw a RuntimeException
      try {
        stateMachine.releaseAndReport("call-1", Duration.ofMillis(10), null);
      } catch (RuntimeException expected) {
        // The abstract base class might catch or propagate depending on your exact implementation,
        // but we primarily care about the internal state afterwards.
      }

      // Then
      // The finally block in AbstractBulkheadStateMachine MUST have executed releasePermitInternal()
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(0);
    }

    @Test
    void should_block_all_requests_when_dynamic_limit_drops_to_zero() throws InterruptedException {
      // Given
      limitAlgorithm.setLimit(0);

      // When
      boolean acquired = stateMachine.tryAcquire("call-1", Duration.ofMillis(10));

      // Then
      // The system should gracefully handle a zero-limit state without errors,
      // simply rejecting all incoming traffic.
      assertThat(acquired).isFalse();
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(0);
    }
  }

  @Nested
  class DynamicLimitAdjustment {

    @Test
    void should_wake_up_waiting_threads_when_limit_increases() throws Exception {
      // Given
      limitAlgorithm.setLimit(1); // Start with limit 1
      stateMachine = new AdaptiveImperativeStateMachine("test-adaptive", config, limitAlgorithm);

      // Consume the only permit so subsequent calls block (activeCalls = 1)
      stateMachine.tryAcquire("call-1", Duration.ZERO);

      // Two threads want a permit. They will both block because the limit is 1.
      CompletableFuture<Boolean> waitingThread1 = CompletableFuture.supplyAsync(() -> {
        try { return stateMachine.tryAcquire("call-2", Duration.ofSeconds(5)); }
        catch (InterruptedException e) { return false; }
      });

      CompletableFuture<Boolean> waitingThread2 = CompletableFuture.supplyAsync(() -> {
        try { return stateMachine.tryAcquire("call-3", Duration.ofSeconds(5)); }
        catch (InterruptedException e) { return false; }
      });

      // Ensure the async threads are actually in the waiting state
      Thread.sleep(100);

      // When
      // Simulate the limit algorithm increasing the capacity from 1 to 3
      limitAlgorithm.setLimit(3);

      // releaseAndReport will release the 1 active permit (activeCalls drops to 0).
      // Crucially, it will also trigger our limit-increase logic which calls signalAll().
      // Without signalAll(), the standard release mechanism would only wake up ONE thread.
      stateMachine.releaseAndReport("call-1", Duration.ofMillis(50), null);

      // Then
      // BOTH waiting threads must have been woken up to acquire a permit
      Boolean acquired1 = waitingThread1.get(1, TimeUnit.SECONDS);
      Boolean acquired2 = waitingThread2.get(1, TimeUnit.SECONDS);

      assertThat(acquired1).isTrue();
      assertThat(acquired2).isTrue();
      // activeCalls went: 1 (start) -> 0 (release) -> 1 (thread 1) -> 2 (thread 2)
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(2);
    }
  }

  @Nested
  class InterruptionHandling {

    @Test
    void should_throw_exception_when_thread_is_interrupted_while_waiting() {
      // Given
      limitAlgorithm.setLimit(0); // Force waiting
      stateMachine = new AdaptiveImperativeStateMachine("test-adaptive", config, limitAlgorithm);

      Thread currentThread = Thread.currentThread();
      CompletableFuture.runAsync(() -> {
        try {
          Thread.sleep(50);
          currentThread.interrupt();
        } catch (InterruptedException ignored) {}
      });

      // When & Then
      assertThatThrownBy(() -> stateMachine.tryAcquire("call-1", Duration.ofSeconds(5)))
          .isInstanceOf(InqBulkheadInterruptedException.class);

      // Clear interrupt status to not affect subsequent tests
      Thread.interrupted();
    }
  }

  // --- Manual Test Doubles (Fakes & Stubs) ---

  private static class FakeLimitAlgorithm implements InqLimitAlgorithm {
    private volatile int limit;
    private volatile boolean throwOnUpdate = false;

    FakeLimitAlgorithm(int limit) {
      this.limit = limit;
    }

    public void setLimit(int limit) {
      this.limit = limit;
    }

    public void setThrowOnUpdate(boolean throwOnUpdate) {
      this.throwOnUpdate = throwOnUpdate;
    }

    @Override
    public int getLimit() {
      return limit;
    }

    @Override
    public void update(Duration rtt, boolean success) {
      if (throwOnUpdate) {
        throw new RuntimeException("Simulated algorithm crash");
      }
    }
  }

  private static class StubClock implements InqClock {
    @Override
    public Instant instant() {
      return Instant.now();
    }
  }
}