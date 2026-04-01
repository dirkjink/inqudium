package eu.inqudium.core.time;

import java.time.Instant;
import java.util.concurrent.locks.LockSupport;

/**
 * A highly optimized, zero-allocation implementation of {@link InqClock}.
 *
 * <p>This clock uses a background virtual thread to periodically update a cached
 * {@link Instant} using {@link LockSupport#parkNanos(long)}. Threads reading the time
 * only perform a volatile variable read, avoiding the high cost of system calls
 * and object allocations.
 *
 * <h2>Singleton Usage</h2>
 * <p>For production, always use the globally shared instance via {@link #getDefault()}.
 * This ensures only a single virtual thread is responsible for updating the time
 * across the entire application.
 *
 * <h2>Safety Mechanism</h2>
 * <p>If the background thread stops unexpectedly or the clock is explicitly closed,
 * it safely falls back to calculating the time on the fly.
 *
 * @since 0.1.0
 */
public final class CachedInqClock implements InqClock, AutoCloseable {

  private final Thread updaterThread;
  private volatile Instant cachedTime;
  private volatile boolean running;
  private volatile boolean active;

  /**
   * Creates a new cached clock with a default 1-millisecond update interval.
   * Visible for testing to avoid cross-test state pollution.
   */
  CachedInqClock() {
    this(1);
  }

  /**
   * Creates a new cached clock with a custom update interval.
   * Visible for testing to avoid cross-test state pollution.
   *
   * @param updateIntervalMillis the interval in milliseconds
   */
  CachedInqClock(long updateIntervalMillis) {
    this.cachedTime = Instant.ofEpochMilli(System.currentTimeMillis());
    this.running = true;
    this.active = true;

    long parkNanos = updateIntervalMillis * 1_000_000L;

    this.updaterThread = Thread.ofVirtual()
        .name("inq-cached-clock-updater")
        .start(() -> {
          try {
            while (this.running && !Thread.interrupted()) {
              LockSupport.parkNanos(parkNanos);
              this.cachedTime = Instant.ofEpochMilli(System.currentTimeMillis());
            }
          } finally {
            this.active = false;
          }
        });
  }

  /**
   * Returns the global default singleton instance of the cached clock.
   *
   * @return the shared cached clock
   */
  public static CachedInqClock getDefault() {
    return InstanceHolder.INSTANCE;
  }

  @Override
  public Instant instant() {
    if (this.active) {
      return this.cachedTime;
    }
    return Instant.ofEpochMilli(System.currentTimeMillis());
  }

  @Override
  public void close() {
    this.running = false;
    this.updaterThread.interrupt();
  }

  /**
   * Lazy initialization holder class idiom for thread-safe singleton instantiation.
   * The background thread is only started when {@link #getDefault()} is called
   * for the very first time.
   */
  private static final class InstanceHolder {
    static final CachedInqClock INSTANCE = new CachedInqClock(1);
  }
}