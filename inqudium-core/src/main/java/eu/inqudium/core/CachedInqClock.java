package eu.inqudium.core;

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
 * <h2>Safety Mechanism</h2>
 * <p>If the background thread stops unexpectedly or the clock is explicitly closed,
 * it safely falls back to calculating the time on the fly using
 * {@link System#currentTimeMillis()}.
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
   */
  public CachedInqClock() {
    this(1);
  }

  /**
   * Creates a new cached clock with a custom update interval.
   *
   * @param updateIntervalMillis the interval in milliseconds
   */
  public CachedInqClock(long updateIntervalMillis) {
    this.cachedTime = Instant.ofEpochMilli(System.currentTimeMillis());
    this.running = true;
    this.active = true;

    long parkNanos = updateIntervalMillis * 1_000_000L;

    this.updaterThread = Thread.ofVirtual()
        .name("inq-cached-clock-updater")
        .start(() -> {
          try {
            // Thread.interrupted() clears the interrupt flag and prevents busy-looping
            while (this.running && !Thread.interrupted()) {
              LockSupport.parkNanos(parkNanos);
              this.cachedTime = Instant.ofEpochMilli(System.currentTimeMillis());
            }
          } finally {
            // Safety fallback: If the thread exits for ANY reason (closed,
            // unexpected interrupt, or crash), we mark the clock as inactive.
            this.active = false;
          }
        });
  }

  @Override
  public Instant instant() {
    if (this.active) {
      // Fast path: Zero-allocation volatile read
      return this.cachedTime;
    }
    // Fallback path: The background thread is dead, calculate time directly
    return Instant.ofEpochMilli(System.currentTimeMillis());
  }

  @Override
  public void close() {
    this.running = false;
    this.updaterThread.interrupt();
  }
}

