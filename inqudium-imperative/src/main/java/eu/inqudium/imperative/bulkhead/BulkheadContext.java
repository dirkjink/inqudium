package eu.inqudium.imperative.bulkhead;

import eu.inqudium.core.element.bulkhead.config.BulkheadEventConfig;
import eu.inqudium.core.element.bulkhead.strategy.BlockingBulkheadStrategy;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.log.Logger;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;

import java.time.Duration;

/**
 * Shared operational context for bulkhead execution strategies.
 *
 * <p>Provides read-only access to the bulkhead's runtime dependencies — strategy,
 * timing, events, and logging. Implemented by {@link ImperativeBulkhead} and consumed
 * by {@link CompletableFutureAsyncExecutor} to avoid passing individual dependencies
 * through constructor parameters.
 *
 * <p>This is a package-private SPI, not a public API. External code interacts with
 * the bulkhead through {@link Bulkhead} and {@link eu.inqudium.imperative.core.InqAsyncExecutor}.
 *
 * @since 0.3.0
 */
interface BulkheadContext {

  boolean isEnableExceptionOptimization();

  /**
   * The bulkhead instance name, used for events, exceptions, and logging.
   */
  String bulkheadName();

  /**
   * The blocking strategy that manages permits.
   */
  BlockingBulkheadStrategy strategy();

  /**
   * Maximum time to wait for a permit before rejecting.
   */
  Duration maxWaitDuration();

  /**
   * Nanosecond time source for RTT measurement (adaptive algorithms).
   */
  InqNanoTimeSource nanoTimeSource();

  /**
   * Controls which event categories are enabled (standard vs diagnostic).
   */
  BulkheadEventConfig eventConfig();

  /**
   * Per-element event publisher for diagnostic events.
   */
  InqEventPublisher eventPublisher();

  /**
   * Clock for event timestamps.
   */
  InqClock clock();

  /**
   * Logger for error reporting in release/event paths.
   */
  Logger logger();
}
