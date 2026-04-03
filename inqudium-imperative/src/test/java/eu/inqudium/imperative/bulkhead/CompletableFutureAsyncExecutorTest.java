package eu.inqudium.imperative.bulkhead;

import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.element.bulkhead.InqBulkheadInterruptedException;
import eu.inqudium.core.element.bulkhead.config.BulkheadEventConfig;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.element.bulkhead.strategy.BlockingBulkheadStrategy;
import eu.inqudium.core.element.bulkhead.strategy.RejectionContext;
import eu.inqudium.core.event.InqEvent;
import eu.inqudium.core.event.InqEventConsumer;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.event.InqSubscription;
import eu.inqudium.core.log.LogAction;
import eu.inqudium.core.log.Logger;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CompletableFutureAsyncExecutor} in isolation from the bulkhead facade.
 *
 * <p>All dependencies are hand-crafted test doubles — no mock libraries. The executor
 * is tested through its {@link eu.inqudium.imperative.core.InqAsyncExecutor} contract
 * using a {@link StubContext} that provides full control over strategy behavior,
 * timing, and event recording.
 */
class CompletableFutureAsyncExecutorTest {

  private StubStrategy strategy;
  private RecordingEventPublisher eventPublisher;
  private ControllableNanoTime nanoTime;
  private StubContext context;
  private CompletableFutureAsyncExecutor executor;

  @BeforeEach
  void setUp() {
    strategy = new StubStrategy();
    eventPublisher = new RecordingEventPublisher();
    nanoTime = new ControllableNanoTime();
    context = new StubContext(strategy, eventPublisher, nanoTime);
    executor = new CompletableFutureAsyncExecutor(context);
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Test doubles
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * Controllable strategy that tracks every acquire/release/onCallComplete invocation.
   * By default, permits are always granted (tryAcquire returns null).
   */
  static class StubStrategy implements BlockingBulkheadStrategy {

    // Recorded state
    final AtomicInteger acquireCount = new AtomicInteger();
    final AtomicInteger releaseCount = new AtomicInteger();
    final AtomicInteger rollbackCount = new AtomicInteger();
    final AtomicInteger concurrentCalls = new AtomicInteger();
    final List<OnCallCompleteRecord> completions =
        Collections.synchronizedList(new ArrayList<>());
    // Configurable behavior
    volatile RejectionContext rejectionToReturn = null;
    volatile boolean throwInterruptOnAcquire = false;
    volatile RuntimeException releaseException = null;
    volatile RuntimeException onCallCompleteException = null;
    volatile Duration lastAcquireTimeout;

    @Override
    public RejectionContext tryAcquire(Duration timeout) throws InterruptedException {
      lastAcquireTimeout = timeout;
      if (throwInterruptOnAcquire) {
        throw new InterruptedException("Stub: interrupted");
      }
      if (rejectionToReturn != null) {
        return rejectionToReturn;
      }
      acquireCount.incrementAndGet();
      concurrentCalls.incrementAndGet();
      return null;
    }

    @Override
    public void release() {
      if (releaseException != null) {
        releaseCount.incrementAndGet();
        concurrentCalls.decrementAndGet();
        throw releaseException;
      }
      releaseCount.incrementAndGet();
      concurrentCalls.decrementAndGet();
    }

    @Override
    public void rollback() {
      rollbackCount.incrementAndGet();
      concurrentCalls.decrementAndGet();
    }

    @Override
    public void onCallComplete(long rttNanos, boolean isSuccess) {
      completions.add(new OnCallCompleteRecord(rttNanos, isSuccess));
      if (onCallCompleteException != null) {
        throw onCallCompleteException;
      }
    }

    @Override
    public int concurrentCalls() {
      return concurrentCalls.get();
    }

    @Override
    public int availablePermits() {
      return 10 - concurrentCalls.get();
    }

    @Override
    public int maxConcurrentCalls() {
      return 10;
    }

    record OnCallCompleteRecord(long rttNanos, boolean success) {
    }
  }

  /**
   * Nanosecond time source that returns pre-programmed values.
   * Each call to {@code now()} returns the next value from the sequence.
   * If the sequence is exhausted, returns 0.
   */
  static class ControllableNanoTime implements InqNanoTimeSource {
    private final AtomicInteger callIndex = new AtomicInteger();
    private final List<Long> values = Collections.synchronizedList(new ArrayList<>());

    void program(long... nanos) {
      values.clear();
      for (long n : nanos) {
        values.add(n);
      }
    }

    @Override
    public long now() {
      int idx = callIndex.getAndIncrement();
      if (idx < values.size()) {
        return values.get(idx);
      }
      // Fallback: auto-incrementing to ensure non-zero RTT
      return 1_000_000L * (idx + 1);
    }
  }

  /**
   * Event publisher that records all published events in order.
   */
  static class RecordingEventPublisher implements InqEventPublisher {
    final List<InqEvent> events = Collections.synchronizedList(new ArrayList<>());
    volatile boolean traceEnabled = false;
    volatile RuntimeException publishException = null;

    @Override
    public void publish(InqEvent event) {
      if (publishException != null) {
        throw publishException;
      }
      events.add(event);
    }

    @Override
    public InqSubscription onEvent(InqEventConsumer consumer) {
      // Not needed for testing
      return null;
    }

    @Override
    public <E extends InqEvent> InqSubscription onEvent(Class<E> eventType, Consumer<E> consumer) {
      // Not needed for testing
      return null;
    }

    @Override
    public <E extends InqEvent> InqSubscription onEvent(Class<E> eventType, Consumer<E> consumer, Duration ttl) {
      return null;
    }

    @Override
    public boolean isTraceEnabled() {
      return traceEnabled;
    }

    @Override
    public void publishTrace(Supplier<? extends InqEvent> eventSupplier) {
      if (traceEnabled) {
        events.add(eventSupplier.get());
      }
    }

    @SuppressWarnings("unchecked")
    <E extends InqEvent> List<E> eventsOfType(Class<E> type) {
      return events.stream()
          .filter(type::isInstance)
          .map(e -> (E) e)
          .toList();
    }
  }

  /**
   * A {@link LogAction} that records all log format strings for assertion.
   * Captures every overload so no call is silently dropped.
   */
  static class RecordingLogAction implements LogAction {
    final List<String> messages = Collections.synchronizedList(new ArrayList<>());

    @Override
    public boolean isEnabled() {
      return true;
    }

    @Override
    public void log(String message) {
      messages.add(message);
    }

    @Override
    public void log(String message, Object arg) {
      messages.add(message);
    }

    @Override
    public void log(String message, Object arg1, Object arg2) {
      messages.add(message);
    }

    @Override
    public void log(String message, Object arg1, Object arg2, Object arg3) {
      messages.add(message);
    }

    @Override
    public void log(String message, java.util.function.Supplier<?> argSupplier) {
      messages.add(message);
    }

    @Override
    public void log(String message, Object... args) {
      messages.add(message);
    }
  }

  /**
   * Test implementation of {@link BulkheadContext} with full control over all dependencies.
   */
  static class StubContext implements BulkheadContext {
    private final StubStrategy strategy;
    private final RecordingEventPublisher eventPublisher;
    private final ControllableNanoTime nanoTime;
    private final RecordingLogAction errorLogAction = new RecordingLogAction();
    private final Logger logger = new Logger(
        Logger.NO_OP_ACTION, Logger.NO_OP_ACTION, Logger.NO_OP_ACTION, errorLogAction);
    private BulkheadEventConfig eventConfig = BulkheadEventConfig.standard();

    StubContext(StubStrategy strategy, RecordingEventPublisher eventPublisher,
                ControllableNanoTime nanoTime) {
      this.strategy = strategy;
      this.eventPublisher = eventPublisher;
      this.nanoTime = nanoTime;
    }

    void useEventConfig(BulkheadEventConfig config) {
      this.eventConfig = config;
    }

    @Override
    public boolean isEnableExceptionOptimization() {
      return false;
    }

    @Override
    public String bulkheadName() {
      return "test-bulkhead";
    }

    @Override
    public BlockingBulkheadStrategy strategy() {
      return strategy;
    }

    @Override
    public Duration maxWaitDuration() {
      return Duration.ofMillis(150);
    }

    @Override
    public InqNanoTimeSource nanoTimeSource() {
      return nanoTime;
    }

    @Override
    public BulkheadEventConfig eventConfig() {
      return eventConfig;
    }

    @Override
    public InqEventPublisher eventPublisher() {
      return eventPublisher;
    }

    @Override
    public InqClock clock() {
      return Instant::now;
    }

    @Override
    public Logger logger() {
      return logger;
    }
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Tests
  // ════════════════════════════════════════════════════════════════════════════

  @Nested
  class ExecuteAsync {

    @Test
    void should_return_the_result_of_the_callable() throws Exception {
      // Given
      var callable = (java.util.concurrent.Callable<String>) () -> "hello";

      // When
      CompletableFuture<String> future = executor.executeAsync(callable);

      // Then
      assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo("hello");
    }

    @Test
    void should_propagate_runtime_exception_from_callable() {
      // Given
      var callable = (java.util.concurrent.Callable<String>) () -> {
        throw new IllegalStateException("boom");
      };

      // When
      CompletableFuture<String> future = executor.executeAsync(callable);

      // Then
      assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("boom");
    }

    @Test
    void should_wrap_checked_exception_from_callable_in_completion_exception() {
      // Given
      var callable = (java.util.concurrent.Callable<String>) () -> {
        throw new IOException("disk full");
      };

      // When
      CompletableFuture<String> future = executor.executeAsync(callable);

      // Then — CompletableFuture.get() unwraps CompletionException automatically,
      // so the cause of the ExecutionException is the original IOException, not a
      // CompletionException wrapper.
      assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(IOException.class)
          .hasRootCauseMessage("disk full");
    }

    @Test
    void should_reject_null_callable() {
      // Given / When / Then
      assertThatThrownBy(() -> executor.executeAsync(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("callable");
    }
  }

  @Nested
  class ExecuteFutureAsync {

    @Test
    void should_preserve_identity_when_supplier_returns_completable_future() throws Exception {
      // Given
      CompletableFuture<String> original = CompletableFuture.completedFuture("identity");

      // When
      CompletableFuture<String> returned = executor.executeFutureAsync(() -> original);

      // Then
      assertThat(returned).isSameAs(original);
      assertThat(returned.get(1, TimeUnit.SECONDS)).isEqualTo("identity");
    }

    @Test
    void should_bridge_plain_future_to_completable_future() throws Exception {
      // Given
      FutureTask<String> plainFuture = new FutureTask<>(() -> "bridged");
      plainFuture.run();

      // When
      CompletableFuture<String> result = executor.executeFutureAsync(() -> plainFuture);

      // Then
      assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo("bridged");
    }

    @Test
    void should_propagate_exception_from_plain_future() {
      // Given
      FutureTask<String> failingFuture = new FutureTask<>(() -> {
        throw new IOException("network error");
      });
      failingFuture.run();

      // When
      CompletableFuture<String> result = executor.executeFutureAsync(() -> failingFuture);

      // Then
      assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class);
    }

    @Test
    void should_release_permit_when_completable_future_completes_successfully()
        throws Exception {
      // Given
      CompletableFuture<String> cf = new CompletableFuture<>();

      // When
      CompletableFuture<String> returned = executor.executeFutureAsync(() -> cf);
      assertThat(strategy.releaseCount.get()).isZero();

      cf.complete("done");
      returned.get(1, TimeUnit.SECONDS);
      Thread.sleep(50); // Allow whenComplete handler to execute

      // Then
      assertThat(strategy.releaseCount.get()).isEqualTo(1);
    }

    @Test
    void should_reject_null_supplier() {
      // Given / When / Then
      assertThatThrownBy(() -> executor.executeFutureAsync(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("futureSupplier");
    }
  }

  @Nested
  class ExecuteCompletionStageAsync {

    @Test
    void should_preserve_pipeline_identity() throws Exception {
      // Given
      CompletableFuture<String> original = new CompletableFuture<>();

      // When
      CompletableFuture<String> returned =
          executor.executeCompletionStageAsync(() -> original);

      // Then
      assertThat(returned).isSameAs(original);

      original.complete("same-object");
      assertThat(returned.get(1, TimeUnit.SECONDS)).isEqualTo("same-object");
    }

    @Test
    void should_return_result_of_completed_stage() throws Exception {
      // Given
      CompletionStage<Integer> stage = CompletableFuture.completedFuture(42);

      // When
      CompletableFuture<Integer> result = executor.executeCompletionStageAsync(() -> stage);

      // Then
      assertThat(result).isSameAs(stage);
      assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo(42);
    }

    @Test
    void should_propagate_failure_from_stage() {
      // Given
      CompletableFuture<String> failed = CompletableFuture.failedFuture(
          new RuntimeException("stage failed"));

      // When
      CompletableFuture<String> result = executor.executeCompletionStageAsync(() -> failed);

      // Then
      assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(RuntimeException.class)
          .hasRootCauseMessage("stage failed");
    }

    @Test
    void should_reject_null_supplier() {
      // Given / When / Then
      assertThatThrownBy(() -> executor.executeCompletionStageAsync(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("stageSupplier");
    }
  }

  @Nested
  class PermitLifecycle {

    @Test
    void should_acquire_permit_before_executing_the_callable() throws Exception {
      // Given
      AtomicBoolean permitWasHeldDuringExecution = new AtomicBoolean(false);

      // When
      CompletableFuture<Void> future = executor.executeAsync(() -> {
        permitWasHeldDuringExecution.set(strategy.concurrentCalls.get() > 0);
        return null;
      });
      future.get(1, TimeUnit.SECONDS);

      // Then
      assertThat(permitWasHeldDuringExecution).isTrue();
      assertThat(strategy.acquireCount.get()).isEqualTo(1);
    }

    @Test
    void should_release_permit_after_successful_completion() throws Exception {
      // Given
      CompletableFuture<String> cf = new CompletableFuture<>();

      // When
      executor.executeCompletionStageAsync(() -> cf);
      assertThat(strategy.concurrentCalls.get())
          .as("Permit held while future is pending")
          .isEqualTo(1);

      cf.complete("done");
      Thread.sleep(50); // Allow whenComplete handler to fire

      // Then
      assertThat(strategy.concurrentCalls.get())
          .as("Permit released after completion")
          .isZero();
      assertThat(strategy.releaseCount.get()).isEqualTo(1);
    }

    @Test
    void should_release_permit_after_exceptional_completion() throws Exception {
      // Given
      CompletableFuture<String> cf = new CompletableFuture<>();

      // When
      executor.executeCompletionStageAsync(() -> cf);
      cf.completeExceptionally(new RuntimeException("failure"));
      Thread.sleep(50);

      // Then
      assertThat(strategy.releaseCount.get()).isEqualTo(1);
      assertThat(strategy.concurrentCalls.get()).isZero();
    }

    @Test
    void should_release_permit_when_future_is_cancelled() throws Exception {
      // Given
      CompletableFuture<String> cf = new CompletableFuture<>();

      // When
      CompletableFuture<String> returned = executor.executeCompletionStageAsync(() -> cf);
      returned.cancel(true);
      Thread.sleep(50);

      // Then
      assertThat(strategy.releaseCount.get()).isEqualTo(1);
      assertThat(strategy.concurrentCalls.get()).isZero();
    }

    @Test
    void should_pass_max_wait_duration_to_strategy() throws Exception {
      // Given / When
      executor.executeAsync(() -> "value").get(1, TimeUnit.SECONDS);

      // Then
      assertThat(strategy.lastAcquireTimeout).isEqualTo(Duration.ofMillis(150));
    }
  }

  @Nested
  class Rejection {

    @Test
    void should_throw_bulkhead_full_exception_when_strategy_rejects() {
      // Given
      strategy.rejectionToReturn = RejectionContext.capacityReached(10, 10);

      // When / Then
      assertThatThrownBy(() -> executor.executeAsync(() -> "value"))
          .isInstanceOf(InqBulkheadFullException.class);
    }

    @Test
    void should_not_acquire_a_permit_when_rejected() {
      // Given
      strategy.rejectionToReturn = RejectionContext.capacityReached(10, 10);

      // When
      try {
        executor.executeAsync(() -> "value");
      } catch (InqBulkheadFullException ignored) {
      }

      // Then
      assertThat(strategy.acquireCount.get()).isZero();
      assertThat(strategy.releaseCount.get()).isZero();
    }

    @Test
    void should_throw_synchronously_on_the_calling_thread() {
      // Given
      strategy.rejectionToReturn = RejectionContext.timeoutExpired(10, 10, 150_000_000L);
      AtomicBoolean thrownOnCallerThread = new AtomicBoolean(false);

      // When
      Thread callerThread = Thread.currentThread();
      try {
        executor.executeAsync(() -> "value");
      } catch (InqBulkheadFullException e) {
        thrownOnCallerThread.set(Thread.currentThread() == callerThread);
      }

      // Then
      assertThat(thrownOnCallerThread).isTrue();
    }

    @Test
    void should_reject_execute_future_async_when_strategy_rejects() {
      // Given
      strategy.rejectionToReturn = RejectionContext.capacityReached(10, 10);

      // When / Then
      assertThatThrownBy(() -> executor.executeFutureAsync(
          () -> CompletableFuture.completedFuture("value")))
          .isInstanceOf(InqBulkheadFullException.class);
    }

    @Test
    void should_reject_execute_completion_stage_async_when_strategy_rejects() {
      // Given
      strategy.rejectionToReturn = RejectionContext.capacityReached(10, 10);

      // When / Then
      assertThatThrownBy(() -> executor.executeCompletionStageAsync(
          () -> CompletableFuture.completedFuture("value")))
          .isInstanceOf(InqBulkheadFullException.class);
    }
  }

  @Nested
  class Interruption {

    @Test
    void should_throw_bulkhead_interrupted_exception_when_thread_is_interrupted() {
      // Given
      strategy.throwInterruptOnAcquire = true;

      // When / Then
      assertThatThrownBy(() -> executor.executeAsync(() -> "value"))
          .isInstanceOf(InqBulkheadInterruptedException.class);
    }

    @Test
    void should_re_set_the_interrupt_flag_on_the_current_thread() {
      // Given
      strategy.throwInterruptOnAcquire = true;

      // When
      try {
        executor.executeAsync(() -> "value");
      } catch (InqBulkheadInterruptedException ignored) {
      }

      // Then
      assertThat(Thread.currentThread().isInterrupted()).isTrue();

      // Cleanup: clear the interrupt flag to not affect other tests
      Thread.interrupted();
    }

    @Test
    void should_not_acquire_a_permit_when_interrupted() {
      // Given
      strategy.throwInterruptOnAcquire = true;

      // When
      try {
        executor.executeAsync(() -> "value");
      } catch (InqBulkheadInterruptedException ignored) {
      }
      Thread.interrupted(); // cleanup

      // Then
      assertThat(strategy.acquireCount.get()).isZero();
      assertThat(strategy.releaseCount.get()).isZero();
    }
  }

  @Nested
  class AdaptiveAlgorithmFeedback {

    @Test
    void should_report_rtt_nanos_to_on_call_complete_on_success() throws Exception {
      // Given
      nanoTime.program(
          1_000_000L,   // acquirePermit → RTT start
          11_000_000L   // releasePermit → RTT end (10ms RTT)
      );

      // When
      CompletableFuture<String> future = executor.executeAsync(() -> "result");
      future.get(1, TimeUnit.SECONDS);
      Thread.sleep(50); // Allow whenComplete handler

      // Then
      assertThat(strategy.completions).hasSize(1);
      assertThat(strategy.completions.getFirst().rttNanos()).isEqualTo(10_000_000L);
      assertThat(strategy.completions.getFirst().success()).isTrue();
    }

    @Test
    void should_report_success_false_when_callable_throws() throws Exception {
      // Given
      nanoTime.program(1_000_000L, 5_000_000L);

      // When
      CompletableFuture<String> future = executor.executeAsync(() -> {
        throw new RuntimeException("fail");
      });
      try {
        future.get(1, TimeUnit.SECONDS);
      } catch (ExecutionException ignored) {
      }
      Thread.sleep(50);

      // Then
      assertThat(strategy.completions).hasSize(1);
      assertThat(strategy.completions.getFirst().success()).isFalse();
    }

    @Test
    void should_still_release_permit_when_on_call_complete_throws() throws Exception {
      // Given
      strategy.onCallCompleteException = new RuntimeException("algorithm broken");

      // When
      CompletableFuture<String> future = executor.executeAsync(() -> "value");
      future.get(1, TimeUnit.SECONDS);
      Thread.sleep(50);

      // Then
      assertThat(strategy.releaseCount.get())
          .as("Permit must be released even if onCallComplete fails")
          .isEqualTo(1);
    }

    @Test
    void should_log_error_when_on_call_complete_throws() throws Exception {
      // Given
      strategy.onCallCompleteException = new RuntimeException("algorithm broken");

      // When
      CompletableFuture<String> future = executor.executeAsync(() -> "value");
      future.get(1, TimeUnit.SECONDS);
      Thread.sleep(50);

      // Then
      assertThat(context.errorLogAction.messages)
          .anyMatch(msg -> msg.contains("Adaptive algorithm hook failed"));
    }
  }

  @Nested
  class DiagnosticEvents {

    @Test
    void should_not_publish_lifecycle_events_in_standard_mode() throws Exception {
      // Given
      context.useEventConfig(BulkheadEventConfig.standard());

      // When
      CompletableFuture<String> future = executor.executeAsync(() -> "value");
      future.get(1, TimeUnit.SECONDS);
      Thread.sleep(50);

      // Then
      assertThat(eventPublisher.eventsOfType(BulkheadOnAcquireEvent.class)).isEmpty();
      assertThat(eventPublisher.eventsOfType(BulkheadOnReleaseEvent.class)).isEmpty();
    }

    @Test
    void should_publish_acquire_and_release_events_in_diagnostic_mode() throws Exception {
      // Given
      context.useEventConfig(BulkheadEventConfig.diagnostic());

      // When
      CompletableFuture<String> future = executor.executeAsync(() -> "value");
      future.get(1, TimeUnit.SECONDS);
      Thread.sleep(50);

      // Then
      assertThat(eventPublisher.eventsOfType(BulkheadOnAcquireEvent.class)).hasSize(1);
      assertThat(eventPublisher.eventsOfType(BulkheadOnReleaseEvent.class)).hasSize(1);
    }

    @Test
    void should_publish_acquire_event_with_bulkhead_name() throws Exception {
      // Given
      context.useEventConfig(BulkheadEventConfig.diagnostic());

      // When
      executor.executeAsync(() -> "value").get(1, TimeUnit.SECONDS);
      Thread.sleep(50);

      // Then
      var acquireEvent = eventPublisher.eventsOfType(BulkheadOnAcquireEvent.class).getFirst();
      assertThat(acquireEvent.getElementName()).isEqualTo("test-bulkhead");
    }

    @Test
    void should_always_publish_rejection_event_in_diagnostic_mode() {
      // Given
      context.useEventConfig(BulkheadEventConfig.diagnostic());
      strategy.rejectionToReturn = RejectionContext.capacityReached(10, 10);

      // When
      try {
        executor.executeAsync(() -> "value");
      } catch (InqBulkheadFullException ignored) {
      }

      // Then
      assertThat(eventPublisher.eventsOfType(BulkheadOnRejectEvent.class)).hasSize(1);
    }

    @Test
    void should_publish_rejection_event_on_interruption_in_diagnostic_mode() {
      // Given
      context.useEventConfig(BulkheadEventConfig.diagnostic());
      strategy.throwInterruptOnAcquire = true;

      // When
      try {
        executor.executeAsync(() -> "value");
      } catch (InqBulkheadInterruptedException ignored) {
      }
      Thread.interrupted(); // cleanup

      // Then
      assertThat(eventPublisher.eventsOfType(BulkheadOnRejectEvent.class)).hasSize(1);
    }

    @Test
    void should_rollback_permit_when_acquire_event_publish_fails_in_diagnostic_mode() {
      // Given
      context.useEventConfig(BulkheadEventConfig.diagnostic());
      eventPublisher.publishException = new RuntimeException("publisher broken");

      // When / Then
      assertThatThrownBy(() -> executor.executeAsync(() -> "value"))
          .isInstanceOf(RuntimeException.class)
          .hasMessage("publisher broken");

      assertThat(strategy.rollbackCount.get())
          .as("Permit must be rolled back when acquire event publish fails")
          .isEqualTo(1);
    }
  }

  @Nested
  class ErrorResilience {

    @Test
    void should_still_release_permit_when_strategy_release_throws() throws Exception {
      // Given
      strategy.releaseException = new RuntimeException("release broken");

      // When
      CompletableFuture<String> future = executor.executeAsync(() -> "value");
      future.get(1, TimeUnit.SECONDS);
      Thread.sleep(50);

      // Then
      assertThat(strategy.releaseCount.get())
          .as("release() was called despite throwing")
          .isEqualTo(1);
    }

    @Test
    void should_log_error_when_strategy_release_throws() throws Exception {
      // Given
      strategy.releaseException = new RuntimeException("release broken");

      // When
      CompletableFuture<String> future = executor.executeAsync(() -> "value");
      future.get(1, TimeUnit.SECONDS);
      Thread.sleep(50);

      // Then
      assertThat(context.errorLogAction.messages)
          .anyMatch(msg -> msg.contains("Strategy release failed"));
    }

    @Test
    void should_log_error_when_release_event_publish_fails_in_diagnostic_mode()
        throws Exception {
      // Given
      context.useEventConfig(BulkheadEventConfig.diagnostic());
      // Allow acquire event, then fail on release event
      var failOnSecondPublish = new RecordingEventPublisher() {
        private final AtomicInteger publishCount = new AtomicInteger();

        @Override
        public void publish(InqEvent event) {
          if (publishCount.incrementAndGet() == 2) {
            throw new RuntimeException("release event publish failed");
          }
          super.publish(event);
        }
      };
      var failContext = new StubContext(strategy, failOnSecondPublish, nanoTime);
      failContext.useEventConfig(BulkheadEventConfig.diagnostic());
      var failExecutor = new CompletableFutureAsyncExecutor(failContext);

      // When
      CompletableFuture<String> future = failExecutor.executeAsync(() -> "value");
      future.get(1, TimeUnit.SECONDS);
      Thread.sleep(50);

      // Then
      assertThat(strategy.releaseCount.get())
          .as("Permit must still be released even if event publish fails")
          .isEqualTo(1);
      assertThat(failContext.errorLogAction.messages)
          .anyMatch(msg -> msg.contains("Failed to publish async release event"));
    }
  }

  @Nested
  class ConcurrentExecution {

    @Test
    void should_track_concurrent_permits_across_multiple_async_calls() throws Exception {
      // Given
      CountDownLatch allStarted = new CountDownLatch(3);
      CountDownLatch proceed = new CountDownLatch(1);

      // When
      CompletableFuture<Void> f1 = executor.executeAsync(() -> {
        allStarted.countDown();
        proceed.await();
        return null;
      });
      CompletableFuture<Void> f2 = executor.executeAsync(() -> {
        allStarted.countDown();
        proceed.await();
        return null;
      });
      CompletableFuture<Void> f3 = executor.executeAsync(() -> {
        allStarted.countDown();
        proceed.await();
        return null;
      });

      assertThat(allStarted.await(1, TimeUnit.SECONDS)).isTrue();

      // Then — all three permits are held concurrently
      assertThat(strategy.acquireCount.get()).isEqualTo(3);
      assertThat(strategy.concurrentCalls.get()).isEqualTo(3);

      // Cleanup
      proceed.countDown();
      CompletableFuture.allOf(f1, f2, f3).get(1, TimeUnit.SECONDS);
      Thread.sleep(50);

      assertThat(strategy.concurrentCalls.get()).isZero();
      assertThat(strategy.releaseCount.get()).isEqualTo(3);
    }
  }
}
