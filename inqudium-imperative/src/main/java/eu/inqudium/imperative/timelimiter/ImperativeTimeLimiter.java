package eu.inqudium.imperative.timelimiter;

import eu.inqudium.core.element.timelimiter.TimeLimiterConfig;
import eu.inqudium.core.element.timelimiter.TimeLimiterEvent;
import eu.inqudium.core.element.timelimiter.TimeLimiterException;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe time limiter facade with pluggable execution strategies.
 *
 * <p>Delegates blocking operations to a {@link TimeLimiterSyncExecutor} and
 * non-blocking operations to a {@link TimeLimiterAsyncExecutor}. Both
 * executors receive a {@link TimeLimiterContext} for access to shared
 * infrastructure (configuration, clock, events, exception creation).
 *
 * <p>The default constructors wire up {@link VirtualThreadSyncExecutor}
 * and {@link CompletableFutureAsyncExecutor}. For custom strategies,
 * use the full constructor or the {@link #builder(TimeLimiterConfig)} API.
 *
 * <h2>Blocking API</h2>
 * <p>{@link #execute(Callable)}, {@link #executeFuture(Supplier)},
 * {@link #executeCompletionStage(Supplier)} — the caller thread blocks
 * until the operation completes, fails, or times out.
 *
 * <h2>Non-blocking API</h2>
 * <p>{@link #executeAsync(Callable)}, {@link #executeFutureAsync(Supplier)},
 * {@link #executeCompletionStageAsync(Supplier)} — returns a
 * {@link CompletableFuture} immediately. Timeout, events, and cancellation
 * are handled in the pipeline.
 */
public class ImperativeTimeLimiter implements TimeLimiterContext {

  private static final Logger LOG = Logger.getLogger(ImperativeTimeLimiter.class.getName());

  private final TimeLimiterConfig config;
  private final Clock clock;
  private final String instanceId;
  private final List<Consumer<TimeLimiterEvent>> eventListeners;
  private final AtomicLong threadCounter = new AtomicLong(0);

  private final TimeLimiterSyncExecutor syncExecutor;
  private final TimeLimiterAsyncExecutor asyncExecutor;

  // ======================== Constructors ========================

  /**
   * Creates a time limiter with the default execution strategies
   * ({@link VirtualThreadSyncExecutor} + {@link CompletableFutureAsyncExecutor}).
   */
  public ImperativeTimeLimiter(TimeLimiterConfig config) {
    this(config, Clock.systemUTC());
  }

  /**
   * Creates a time limiter with the default execution strategies and a custom clock.
   */
  public ImperativeTimeLimiter(TimeLimiterConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.instanceId = UUID.randomUUID().toString();
    this.eventListeners = new CopyOnWriteArrayList<>();
    this.syncExecutor = new VirtualThreadSyncExecutor(this);
    this.asyncExecutor = new CompletableFutureAsyncExecutor(this);
  }

  /**
   * Creates a time limiter with custom execution strategies.
   *
   * <p>The strategies are constructed with {@code this} as the
   * {@link TimeLimiterContext}. Use the factory functions to defer
   * construction until the context is fully initialized:
   *
   * <pre>{@code
   * new ImperativeTimeLimiter(config, clock,
   *     ctx -> new CustomSyncExecutor(ctx),
   *     ctx -> new CustomAsyncExecutor(ctx));
   * }</pre>
   *
   * @param config       the time limiter configuration
   * @param clock        the clock for timestamps
   * @param syncFactory  factory that creates the sync executor from the context
   * @param asyncFactory factory that creates the async executor from the context
   */
  public ImperativeTimeLimiter(
      TimeLimiterConfig config,
      Clock clock,
      java.util.function.Function<TimeLimiterContext, TimeLimiterSyncExecutor> syncFactory,
      java.util.function.Function<TimeLimiterContext, TimeLimiterAsyncExecutor> asyncFactory) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.instanceId = UUID.randomUUID().toString();
    this.eventListeners = new CopyOnWriteArrayList<>();
    this.syncExecutor = Objects.requireNonNull(syncFactory.apply(this), "syncExecutor must not be null");
    this.asyncExecutor = Objects.requireNonNull(asyncFactory.apply(this), "asyncExecutor must not be null");
  }

  // ======================== TimeLimiterContext implementation ========================

  private static void validateTimeout(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive, got " + timeout);
    }
  }

  @Override
  public TimeLimiterConfig config() {
    return config;
  }

  @Override
  public Clock clock() {
    return clock;
  }

  @Override
  public String instanceId() {
    return instanceId;
  }

  @Override
  public void emitEvent(TimeLimiterEvent event) {
    for (Consumer<TimeLimiterEvent> listener : eventListeners) {
      try {
        listener.accept(event);
      } catch (Throwable t) {
        LOG.log(Level.WARNING,
            "Event listener threw exception for time limiter '%s' (event: %s): %s"
                .formatted(config.name(), event.type(), t.getMessage()), t);
      }
    }
  }

  @Override
  public RuntimeException createTimeoutException(Duration effectiveTimeout) {
    RuntimeException exception = config.createTimeoutException(effectiveTimeout);
    if (exception instanceof TimeLimiterException tle) {
      return tle.withInstanceId(instanceId);
    }
    return exception;
  }

  @Override
  public void cancelSafely(Future<?> future) {
    try {
      future.cancel(true);
    } catch (Throwable t) {
      LOG.log(Level.WARNING,
          "Failed to cancel task for time limiter '%s': %s"
              .formatted(config.name(), t.getMessage()), t);
    }
  }

  @Override
  public String nextThreadName() {
    return "timelimiter-%s-%d".formatted(config.name(), threadCounter.incrementAndGet());
  }

  // ======================== Blocking API — Callable ========================

  @Override
  public String nextBridgeThreadName() {
    return "timelimiter-%s-bridge-%d".formatted(config.name(), threadCounter.incrementAndGet());
  }

  public <T> T execute(Callable<T> callable) throws Exception {
    return execute(callable, config.timeout());
  }

  public <T> T execute(Callable<T> callable, Duration timeout) throws Exception {
    Objects.requireNonNull(callable, "callable must not be null");
    validateTimeout(timeout);
    return syncExecutor.execute(callable, timeout);
  }

  public void execute(Runnable runnable) {
    Objects.requireNonNull(runnable, "runnable must not be null");
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ======================== Blocking API — External Future ========================

  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (TimeLimiterException e) {
      if (isOwnException(e)) return fallback.get();
      throw e;
    }
  }

  public <T> T executeFuture(Supplier<Future<T>> futureSupplier) throws Exception {
    return executeFuture(futureSupplier, config.timeout());
  }

  // ======================== Blocking API — CompletionStage ========================

  public <T> T executeFuture(Supplier<Future<T>> futureSupplier, Duration timeout) throws Exception {
    Objects.requireNonNull(futureSupplier, "futureSupplier must not be null");
    validateTimeout(timeout);
    return syncExecutor.executeFuture(futureSupplier, timeout);
  }

  public <T> T executeCompletionStage(Supplier<CompletionStage<T>> stageSupplier) throws Exception {
    return executeCompletionStage(stageSupplier, config.timeout());
  }

  // ======================== Non-blocking API — Callable ========================

  public <T> T executeCompletionStage(
      Supplier<CompletionStage<T>> stageSupplier, Duration timeout) throws Exception {
    Objects.requireNonNull(stageSupplier, "stageSupplier must not be null");
    validateTimeout(timeout);
    return syncExecutor.executeCompletionStage(stageSupplier, timeout);
  }

  public <T> CompletableFuture<T> executeAsync(Callable<T> callable) {
    return executeAsync(callable, config.timeout());
  }

  public <T> CompletableFuture<T> executeAsync(Callable<T> callable, Duration timeout) {
    Objects.requireNonNull(callable, "callable must not be null");
    validateTimeout(timeout);
    return asyncExecutor.executeAsync(callable, timeout);
  }

  public CompletableFuture<Void> executeAsync(Runnable runnable) {
    return executeAsync(runnable, config.timeout());
  }

  // ======================== Non-blocking API — External Future ========================

  public CompletableFuture<Void> executeAsync(Runnable runnable, Duration timeout) {
    Objects.requireNonNull(runnable, "runnable must not be null");
    return executeAsync(() -> {
      runnable.run();
      return null;
    }, timeout);
  }

  public <T> CompletableFuture<T> executeFutureAsync(Supplier<Future<T>> futureSupplier) {
    return executeFutureAsync(futureSupplier, config.timeout());
  }

  // ======================== Non-blocking API — CompletionStage ========================

  public <T> CompletableFuture<T> executeFutureAsync(
      Supplier<Future<T>> futureSupplier, Duration timeout) {
    Objects.requireNonNull(futureSupplier, "futureSupplier must not be null");
    validateTimeout(timeout);
    return asyncExecutor.executeFutureAsync(futureSupplier, timeout);
  }

  public <T> CompletableFuture<T> executeCompletionStageAsync(
      Supplier<CompletionStage<T>> stageSupplier) {
    return executeCompletionStageAsync(stageSupplier, config.timeout());
  }

  // ======================== Listeners & Introspection ========================

  public <T> CompletableFuture<T> executeCompletionStageAsync(
      Supplier<CompletionStage<T>> stageSupplier, Duration timeout) {
    Objects.requireNonNull(stageSupplier, "stageSupplier must not be null");
    validateTimeout(timeout);
    return asyncExecutor.executeCompletionStageAsync(stageSupplier, timeout);
  }

  public Runnable onEvent(Consumer<TimeLimiterEvent> listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    eventListeners.add(listener);
    return () -> eventListeners.remove(listener);
  }

  public TimeLimiterConfig getConfig() {
    return config;
  }

  public String getInstanceId() {
    return instanceId;
  }

  /**
   * Returns the sync executor for introspection or testing.
   */
  public TimeLimiterSyncExecutor getSyncExecutor() {
    return syncExecutor;
  }

  // ======================== Internal ========================

  /**
   * Returns the async executor for introspection or testing.
   */
  public TimeLimiterAsyncExecutor getAsyncExecutor() {
    return asyncExecutor;
  }

  private boolean isOwnException(TimeLimiterException e) {
    if (e.getInstanceId() != null) {
      return Objects.equals(e.getInstanceId(), this.instanceId);
    }
    return Objects.equals(e.getTimeLimiterName(), config.name());
  }
}
