package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.InqBulkheadInterruptedException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ImperativeBulkheadStateMachineTest {

  @Nested
  class NonBlockingAcquisition {

    @Test
    void acquires_a_permit_instantly_if_capacity_is_available() {
      // Given
      BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(1).build();
      ImperativeBulkheadStateMachine stateMachine = new ImperativeBulkheadStateMachine("test", config);

      // When
      boolean acquired = stateMachine.tryAcquireNonBlocking("call-1");

      // Then
      assertThat(acquired).isTrue();
      assertThat(stateMachine.getAvailablePermits()).isZero();
      assertThat(stateMachine.getConcurrentCalls()).isEqualTo(1);
    }

    @Test
    void returns_false_instantly_if_the_bulkhead_is_full() {
      // Given
      BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(1).build();
      ImperativeBulkheadStateMachine stateMachine = new ImperativeBulkheadStateMachine("test", config);
      stateMachine.tryAcquireNonBlocking("call-1"); // Exhaust capacity

      // When
      boolean acquired = stateMachine.tryAcquireNonBlocking("call-2");

      // Then
      assertThat(acquired).isFalse();
    }
  }

  @Nested
  class BlockingAcquisition {

    @Test
    void waits_for_a_permit_and_returns_false_if_the_timeout_expires() throws Exception {
      // Given
      BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(1).build();
      ImperativeBulkheadStateMachine stateMachine = new ImperativeBulkheadStateMachine("test", config);
      stateMachine.tryAcquireNonBlocking("call-1"); // Exhaust capacity

      // When
      // We attempt to block for a very short time
      boolean acquired = stateMachine.tryAcquireBlocking("call-2", Duration.ofMillis(10));

      // Then
      // It must return false after the timeout expires
      assertThat(acquired).isFalse();
    }

    @Test
    void throws_an_interrupted_exception_if_the_waiting_thread_is_interrupted() throws Exception {
      // Given
      BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(0).build();
      ImperativeBulkheadStateMachine stateMachine = new ImperativeBulkheadStateMachine("test", config);

      AtomicReference<Throwable> caughtException = new AtomicReference<>();
      CountDownLatch threadStarted = new CountDownLatch(1);
      CountDownLatch threadFinished = new CountDownLatch(1);

      // A background thread that will wait for a long time
      Thread waitingThread = new Thread(() -> {
        threadStarted.countDown();
        try {
          stateMachine.tryAcquireBlocking("call-interrupted", Duration.ofSeconds(10));
        } catch (Throwable t) {
          caughtException.set(t);
        } finally {
          threadFinished.countDown();
        }
      });

      // When
      waitingThread.start();
      threadStarted.await(); // Ensure the thread is actually running and blocking
      waitingThread.interrupt(); // Interrupt the blocked thread
      threadFinished.await(2, TimeUnit.SECONDS);

      // Then
      // It must throw our specific framework exception
      assertThat(caughtException.get())
          .isInstanceOf(InqBulkheadInterruptedException.class)
          .hasMessageContaining("Thread interrupted while waiting");
    }
  }
}
