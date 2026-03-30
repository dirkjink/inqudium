package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.InqClock;
import eu.inqudium.core.bulkhead.BulkheadConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CoDelImperativeStateMachineTest {

  private AtomicLong simulatedTimeNanos;
  private CoDelImperativeStateMachine stateMachine;

  private static final Duration TARGET_DELAY = Duration.ofMillis(5);
  private static final Duration INTERVAL = Duration.ofMillis(100);

  @BeforeEach
  void setUp() {
    // Given
    simulatedTimeNanos = new AtomicLong(0);

    // TODO: Adapt this to your actual constructor or builder for the final BulkheadConfig class
    BulkheadConfig config = BulkheadConfig.builder()
        .maxConcurrentCalls(1)
        .nanoTimeSource(simulatedTimeNanos::get)
        .clock(new StubClock())
        .build();

    stateMachine = new CoDelImperativeStateMachine("test-codel", config, TARGET_DELAY, INTERVAL);
  }

  @Nested
  class FastExecution {

    @Test
    void should_acquire_permit_when_queue_is_fast_and_below_target_delay() throws InterruptedException {
      // Given
      String callId = "call-1";
      Duration timeout = Duration.ofSeconds(1);

      // When
      boolean acquired = stateMachine.tryAcquire(callId, timeout);

      // Then
      assertThat(acquired).isTrue();
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(1);
    }
  }

  @Nested
  class EdgeCases {

    @Test
    void should_prevent_active_calls_from_dropping_below_zero_on_over_release() {
      // Given
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(0);

      // When
      // Calling release twice without acquiring
      stateMachine.releaseAndReport("phantom-call-1", Duration.ofMillis(10), null);
      stateMachine.releaseAndReport("phantom-call-2", Duration.ofMillis(10), null);

      // Then
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(0);
      assertThat(stateMachine.getAvailablePermits()).isEqualTo(1);
    }

    @Test
    void should_not_trigger_dropping_state_if_wait_time_is_exactly_the_target_delay() throws InterruptedException {
      // Given
      stateMachine.tryAcquire("blocking-call", Duration.ZERO);

      // Set time exactly to the boundary (Wait Time == Target Delay)
      long exactBoundaryTime = TARGET_DELAY.toNanos();
      simulatedTimeNanos.addAndGet(exactBoundaryTime);

      stateMachine.releaseAndReport("blocking-call", Duration.ofMillis(10), null);

      // When
      boolean acquired = stateMachine.tryAcquire("borderline-call", Duration.ofSeconds(1));

      // Then
      // CoDel condition is `waitTimeNanos > targetDelayNanos`, so exact matches should pass
      assertThat(acquired).isTrue();
    }

    @Test
    void should_drain_multiple_requests_rapidly_via_signal_chain_when_in_dropping_state() throws Exception {
      // Given
      stateMachine.tryAcquire("blocking-call", Duration.ZERO);

      // 1. Establish dropping state (first slow call triggers interval)
      CompletableFuture.supplyAsync(() -> {
        try { return stateMachine.tryAcquire("slow-call-1", Duration.ofSeconds(5)); }
        catch (InterruptedException e) { return false; }
      });
      Thread.sleep(50);
      simulatedTimeNanos.addAndGet(TARGET_DELAY.toNanos() + 1);
      stateMachine.releaseAndReport("blocking-call", Duration.ofMillis(10), null);

      // 2. Queue up MULTIPLE threads waiting for permits
      CompletableFuture<Boolean> rejected1 = CompletableFuture.supplyAsync(() -> {
        try { return stateMachine.tryAcquire("rejected-1", Duration.ofSeconds(5)); }
        catch (InterruptedException e) { return false; }
      });
      CompletableFuture<Boolean> rejected2 = CompletableFuture.supplyAsync(() -> {
        try { return stateMachine.tryAcquire("rejected-2", Duration.ofSeconds(5)); }
        catch (InterruptedException e) { return false; }
      });
      Thread.sleep(50);

      // Advance time past the entire interval window
      simulatedTimeNanos.addAndGet(INTERVAL.toNanos() + 1);

      // When
      // Releasing the permit wakes up the FIRST queued thread ('rejected1').
      // Since we implemented the pass-the-baton (permitAvailable.signal()) inside the CoDel rejection block,
      // 'rejected1' will reject itself AND wake up 'rejected2', which will also reject itself.
      stateMachine.releaseAndReport("slow-call-1", Duration.ofMillis(10), null);

      // Then
      // BOTH queued threads must be rejected rapidly without us needing to release more permits manually
      assertThat(rejected1.get(1, TimeUnit.SECONDS)).isFalse();
      assertThat(rejected2.get(1, TimeUnit.SECONDS)).isFalse();

      // The queue has successfully drained itself
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(0);
    }
  }

  @Nested
  class BufferbloatProtection {

    @Test
    void should_allow_first_slow_request_to_pass_but_start_the_interval_stopwatch() throws Exception {
      // Given
      stateMachine.tryAcquire("blocking-call", Duration.ZERO); // Consume the single permit

      // This async call will immediately enter the waiting queue because the permit is taken
      CompletableFuture<Boolean> slowCall = CompletableFuture.supplyAsync(() -> {
        try {
          return stateMachine.tryAcquire("slow-call-1", Duration.ofSeconds(5));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      });

      // Ensure the thread actually started and is waiting in the queue
      Thread.sleep(100);

      // Advance time past the target delay, but remain within the interval
      simulatedTimeNanos.addAndGet(TARGET_DELAY.toNanos() + Duration.ofMillis(1).toNanos());

      // When
      // Releasing the permit wakes up the async thread.
      // It will evaluate its wait time, which is now > TARGET_DELAY.
      stateMachine.releaseAndReport("blocking-call", Duration.ofMillis(10), null);

      // Then
      // CoDel allows bursts, so the first slow request is permitted
      assertThat(slowCall.get(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void should_reject_requests_when_sojourn_time_exceeds_target_for_longer_than_interval() throws Exception {
      // Given
      stateMachine.tryAcquire("blocking-call", Duration.ZERO);

      // 1. Trigger the interval stopwatch with a first slow request
      CompletableFuture<Boolean> slowCall1 = CompletableFuture.supplyAsync(() -> {
        try { return stateMachine.tryAcquire("slow-call-1", Duration.ofSeconds(5)); }
        catch (InterruptedException e) { return false; }
      });
      Thread.sleep(50);
      simulatedTimeNanos.addAndGet(TARGET_DELAY.toNanos() + Duration.ofMillis(1).toNanos());
      stateMachine.releaseAndReport("blocking-call", Duration.ofMillis(10), null);
      assertThat(slowCall1.get(1, TimeUnit.SECONDS)).isTrue(); // slow-call-1 now holds the permit

      // 2. Enqueue the NEXT request. This one will wait while the interval expires.
      CompletableFuture<Boolean> rejectedCall = CompletableFuture.supplyAsync(() -> {
        try { return stateMachine.tryAcquire("rejected-call", Duration.ofSeconds(5)); }
        catch (InterruptedException e) { return false; }
      });
      Thread.sleep(50);

      // Advance time past the entire interval window
      simulatedTimeNanos.addAndGet(INTERVAL.toNanos() + Duration.ofMillis(1).toNanos());

      // When
      // Releasing the permit wakes up 'rejectedCall'.
      // It sees its wait time is > TARGET_DELAY AND the interval has passed since the stopwatch started.
      stateMachine.releaseAndReport("slow-call-1", Duration.ofMillis(10), null);

      // Then
      // CoDel enters the dropping state to drain the queue
      assertThat(rejectedCall.get(1, TimeUnit.SECONDS)).isFalse();
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(0);
    }

    @Test
    void should_recover_when_sojourn_time_returns_to_normal() throws Exception {
      // Given
      stateMachine.tryAcquire("blocking-call", Duration.ZERO);

      // Start stopwatch with a slow request
      CompletableFuture<Boolean> slowCall = CompletableFuture.supplyAsync(() -> {
        try { return stateMachine.tryAcquire("slow-call", Duration.ofSeconds(5)); }
        catch (InterruptedException e) { return false; }
      });
      Thread.sleep(50);
      simulatedTimeNanos.addAndGet(TARGET_DELAY.toNanos() + Duration.ofMillis(1).toNanos());
      stateMachine.releaseAndReport("blocking-call", Duration.ofMillis(10), null);
      assertThat(slowCall.get(1, TimeUnit.SECONDS)).isTrue();

      // Advance time to simulate sustained congestion passing by
      simulatedTimeNanos.addAndGet(INTERVAL.toNanos() + Duration.ofMillis(50).toNanos());
      stateMachine.releaseAndReport("slow-call", Duration.ofMillis(10), null);

      // The queue is now EMPTY.

      // When
      // The next request arrives and acquires immediately (simulating an empty queue/fast response)
      // Its wait time is 0.
      boolean acquired = stateMachine.tryAcquire("fast-call", Duration.ofSeconds(1));

      // Then
      // The stopwatch must reset because the wait time (0) was below the target delay
      assertThat(acquired).isTrue();
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(1);
    }
  }

  // --- Manual Test Doubles (Fakes & Stubs) ---

  private static class StubClock implements InqClock {
    @Override
    public Instant instant() {
      return Instant.now();
    }
  }
}
