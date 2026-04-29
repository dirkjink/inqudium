package eu.inqudium.imperative.ratelimiter;

import eu.inqudium.core.element.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.element.ratelimiter.RateLimiterEvent;
import eu.inqudium.core.element.ratelimiter.RateLimiterException;
import eu.inqudium.core.element.ratelimiter.strategy.TokenBucketState;
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
    // Standard Execution & Multi-Permits (Using Default Algorithm)
    // ================================================================

    @Nested
    @DisplayName("Standard Execution & Multi-Permits")
    class ExecutionAndPermits {

        @Test
        @DisplayName("Should successfully execute a callable when permits are available")
        void should_successfully_execute_a_callable_when_permits_are_available() throws Exception {
            // Given — Strategy is implicitly TokenBucket via the builder default
            RateLimiterConfig<TokenBucketState> config = RateLimiterConfig.builder("test")
                    .capacity(5)
                    .build();
            var limiter = new ImperativeRateLimiter<>(config);

            // When
            String result = limiter.execute(() -> "success");

            // Then
            assertThat(result).isEqualTo("success");
            assertThat(limiter.getAvailablePermits()).isEqualTo(4); // 1 consumed
        }

        @Test
        @DisplayName("Should allow consuming multiple permits in a single execution")
        void should_allow_consuming_multiple_permits_in_a_single_execution() throws Exception {
            // Given
            RateLimiterConfig<TokenBucketState> config = RateLimiterConfig.builder("multi")
                    .capacity(10)
                    .build();
            var limiter = new ImperativeRateLimiter<>(config);

            // When
            limiter.execute(() -> "batch-task", 5);

            // Then
            assertThat(limiter.getAvailablePermits()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should throw RateLimiterException if multi permit request exceeds timeout")
        void should_throw_rate_limiter_exception_if_multi_permit_request_exceeds_timeout() {
            // Given
            RateLimiterConfig<TokenBucketState> config = RateLimiterConfig.builder("fail-fast")
                    .capacity(5)
                    .refillPermits(1)
                    .refillPeriod(Duration.ofSeconds(10))
                    .defaultTimeout(Duration.ZERO) // Fail fast
                    .build();
            var limiter = new ImperativeRateLimiter<>(config);

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
    // Interrupt Handling & Token Refund
    // ================================================================

    @Nested
    @DisplayName("Interrupt Handling and Token Refund")
    class InterruptAndRefund {

        @Test
        @DisplayName("Should throw InterruptedException and refund token if thread is interrupted while waiting")
        void should_throw_interrupted_exception_and_refund_token_if_thread_is_interrupted_while_waiting() throws InterruptedException {
            // Given — capacity 1, slow refill
            RateLimiterConfig<TokenBucketState> config = RateLimiterConfig.builder("interrupt-test")
                    .capacity(1)
                    .refillPermits(1)
                    .refillPeriod(Duration.ofSeconds(10))
                    .build();
            var limiter = new ImperativeRateLimiter<>(config);

            // Empty the bucket completely
            limiter.drain();

            AtomicBoolean caughtInterrupt = new AtomicBoolean(false);
            CountDownLatch threadStarted = new CountDownLatch(1);

            // When
            Thread worker = new Thread(() -> {
                try {
                    threadStarted.countDown();
                    limiter.execute(() -> "task", 1, Duration.ofSeconds(15));
                } catch (InterruptedException e) {
                    caughtInterrupt.set(true);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            worker.start();

            threadStarted.await();
            Thread.sleep(100);

            worker.interrupt();
            worker.join();

            // Then — interrupt caught, permits refunded back to zero (was -1 during reservation)
            assertThat(caughtInterrupt).isTrue();
            assertThat(limiter.getState().availablePermits()).isZero();
        }
    }

    // ================================================================
    // Reset, Drain & Unparking
    // ================================================================

    @Nested
    @DisplayName("Drain, Reset & Thread Unparking")
    class Unparking {

        @Test
        @DisplayName("Should wake up blocked threads immediately when reset is called")
        void should_wake_up_blocked_threads_immediately_when_reset_is_called() throws InterruptedException {
            // Given
            RateLimiterConfig<TokenBucketState> config = RateLimiterConfig.builder("unpark-test")
                    .capacity(1)
                    .refillPermits(1)
                    .refillPeriod(Duration.ofDays(1))
                    .build();
            var limiter = new ImperativeRateLimiter<>(config);
            limiter.drain();

            CountDownLatch threadFinished = new CountDownLatch(1);

            Thread worker = new Thread(() -> {
                try {
                    limiter.execute(() -> "task", 1, Duration.ofDays(2));
                    threadFinished.countDown();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            worker.start();

            Thread.sleep(100);

            // When — reset increments epoch and unparks waiting threads
            limiter.reset();

            // Then — thread wakes up, detects epoch change, retries against fresh bucket
            boolean finishedQuickly = threadFinished.await(2, TimeUnit.SECONDS);
            assertThat(finishedQuickly).isTrue();
        }
    }

    // ================================================================
    // Observability & Error Swallowing
    // ================================================================

    @Nested
    @DisplayName("Observability Isolation")
    class ObservabilityIsolation {

        @Test
        @DisplayName("Should isolate fatal errors in event listeners so execution proceeds safely")
        void should_isolate_fatal_errors_in_event_listeners_so_execution_proceeds_safely() throws Exception {
            // Given
            RateLimiterConfig<TokenBucketState> config = RateLimiterConfig.builder("events")
                    .capacity(5)
                    .build();
            var limiter = new ImperativeRateLimiter<>(config);

            List<RateLimiterEvent> events = new ArrayList<>();

            limiter.onEvent(events::add);
            limiter.onEvent(event -> {
                throw new Error("Fatal Telemetry Error");
            });

            // When
            String result = limiter.execute(() -> "survived");

            // Then
            assertThat(result).isEqualTo("survived");
            assertThat(events).hasSize(1);
        }
    }
}
