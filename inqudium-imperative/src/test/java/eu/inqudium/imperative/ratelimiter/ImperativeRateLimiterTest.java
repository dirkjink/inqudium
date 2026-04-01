package eu.inqudium.imperative.ratelimiter;

import eu.inqudium.core.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.ratelimiter.RateLimiterEvent;
import eu.inqudium.core.ratelimiter.RateLimiterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ImperativeRateLimiter — Thread-safe State and Blocking Logic")
class ImperativeRateLimiterTest {

  // ================================================================
  // Standard Execution & Multi-Permits
  // ================================================================

  @Nested
  @DisplayName("Standard Execution & Multi-Permits")
  class ExecutionAndPermits {

    @Test
    @DisplayName("should successfully execute a callable when permits are available")
    void should_successfully_execute_a_callable_when_permits_are_available() throws Exception {
      // Given
      RateLimiterConfig config = RateLimiterConfig.builder("test").capacity(5).build();
      ImperativeRateLimiter limiter = new ImperativeRateLimiter(config);

      // When
      String result = limiter.execute(() -> "success");

      // Then
      assertThat(result).isEqualTo("success");
      assertThat(limiter.getAvailablePermits()).isEqualTo(4); // 1 consumed
    }

    @Test
    @DisplayName("should allow consuming multiple permits in a single execution")
    void should_allow_consuming_multiple_permits_in_a_single_execution() throws Exception {
      // Given
      RateLimiterConfig config = RateLimiterConfig.builder("multi").capacity(10).build();
      ImperativeRateLimiter limiter = new ImperativeRateLimiter(config);

      // When
      limiter.execute(() -> "batch-task", 5);

      // Then
      assertThat(limiter.getAvailablePermits()).isEqualTo(5);
    }

    @Test
    @DisplayName("should throw RateLimiterException if multi permit request exceeds timeout")
    void should_throw_rate_limiter_exception_if_multi_permit_request_exceeds_timeout() {
      // Given
      RateLimiterConfig config = RateLimiterConfig.builder("fail-fast")
          .capacity(5)
          .refillPermits(1)
          .refillPeriod(Duration.ofSeconds(10))
          .defaultTimeout(Duration.ZERO) // Fail fast
          .build();
      ImperativeRateLimiter limiter = new ImperativeRateLimiter(config);

      // Drain the bucket first
      limiter.drain();

      // When / Then
      assertThatThrownBy(() -> limiter.execute(() -> "task", 2, Duration.ZERO))
          .isInstanceOf(RateLimiterException.class);

      // Bucket should still be empty, no debt generated due to fail-fast
      assertThat(limiter.getAvailablePermits()).isZero();
    }
  }

  // ================================================================
  // Interrupt Handling & Token Refund (The critical fix)
  // ================================================================

  @Nested
  @DisplayName("Interrupt Handling and Token Refund")
  class InterruptAndRefund {

    @Test
    @DisplayName("should throw InterruptedException and refund token if thread is interrupted while waiting")
    void should_throw_interrupted_exception_and_refund_token_if_thread_is_interrupted_while_waiting() throws InterruptedException {
      // Given — capacity 1, slow refill
      RateLimiterConfig config = RateLimiterConfig.builder("interrupt-test")
          .capacity(1)
          .refillPermits(1)
          .refillPeriod(Duration.ofSeconds(10))
          .build();
      ImperativeRateLimiter limiter = new ImperativeRateLimiter(config);

      // Empty the bucket completely
      limiter.drain();

      AtomicBoolean caughtInterrupt = new AtomicBoolean(false);
      CountDownLatch threadStarted = new CountDownLatch(1);

      // When
      Thread worker = new Thread(() -> {
        try {
          threadStarted.countDown();
          // Ask for 1 permit. It will reserve it (debt goes to -1) and park for 10 seconds.
          limiter.execute(() -> "task", 1, Duration.ofSeconds(15));
        } catch (InterruptedException e) {
          caughtInterrupt.set(true);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      worker.start();

      // Wait for thread to actually enter the park loop
      threadStarted.await();
      Thread.sleep(100);

      // Interrupt the worker while it's waiting for the token
      worker.interrupt();
      worker.join();

      // Then
      assertThat(caughtInterrupt).isTrue();
      // Crucial part of Fix 1: The permit that was reserved MUST be refunded.
      // Since it was drained to 0, and the thread reserved 1 (going to -1 conceptually),
      // the refund should put it back to 0. It should NOT be stuck at -1.
      assertThat(limiter.getSnapshot().availablePermits()).isZero();
    }
  }

  // ================================================================
  // Reset, Drain & Unparking
  // ================================================================

  @Nested
  @DisplayName("Drain, Reset & Thread Unparking")
  class Unparking {

    @Test
    @DisplayName("should wake up blocked threads immediately when reset is called")
    void should_wake_up_blocked_threads_immediately_when_reset_is_called() throws InterruptedException {
      // Given
      RateLimiterConfig config = RateLimiterConfig.builder("unpark-test")
          .capacity(1)
          .refillPermits(1)
          .refillPeriod(Duration.ofDays(1)) // Very long wait
          .build();
      ImperativeRateLimiter limiter = new ImperativeRateLimiter(config);
      limiter.drain(); // Empty the bucket

      CountDownLatch threadFinished = new CountDownLatch(1);

      Thread worker = new Thread(() -> {
        try {
          // Will block for 1 day
          limiter.execute(() -> "task", 1, Duration.ofDays(2));
          threadFinished.countDown();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      worker.start();

      // Wait to ensure thread is parked
      Thread.sleep(100);

      // When
      limiter.reset(); // This should trigger LockSupport.unpark(thread)

      // Then
      boolean finishedQuickly = threadFinished.await(2, TimeUnit.SECONDS);
      assertThat(finishedQuickly).isTrue(); // Thread woke up and consumed the reset token
    }
  }

  // ================================================================
  // Observability & Error Swallowing
  // ================================================================

  @Nested
  @DisplayName("Observability Isolation")
  class ObservabilityIsolation {

    @Test
    @DisplayName("should isolate fatal errors in event listeners so execution proceeds safely")
    void should_isolate_fatal_errors_in_event_listeners_so_execution_proceeds_safely() throws Exception {
      // Given
      RateLimiterConfig config = RateLimiterConfig.builder("events").capacity(5).build();
      ImperativeRateLimiter limiter = new ImperativeRateLimiter(config);

      List<RateLimiterEvent> events = new ArrayList<>();

      // Normal listener
      limiter.onEvent(events::add);
      // Malicious listener throwing a severe Error (simulating an agent crash)
      limiter.onEvent(event -> {
        throw new Error("Fatal Telemetry Error");
      });

      // When
      String result = limiter.execute(() -> "survived");

      // Then
      assertThat(result).isEqualTo("survived");
      assertThat(events).hasSize(1); // The healthy listener still got the event
    }
  }
}