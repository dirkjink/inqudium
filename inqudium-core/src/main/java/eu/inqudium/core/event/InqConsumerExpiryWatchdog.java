package eu.inqudium.core.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Background watchdog that periodically sweeps expired TTL-based consumers
 * from a {@link DefaultInqEventPublisher}.
 *
 * <p>Runs on a virtual thread (Project Loom) with a configurable check interval.
 * The watchdog is designed to be started lazily — only when the first TTL-based
 * subscription is registered — and stopped via {@link #close()}.
 *
 * <h2>Thread safety</h2>
 * <p>The watchdog is fully thread-safe. The {@link #close()} method can be called
 * from any thread and causes the virtual thread to terminate gracefully. Calling
 * {@code close()} multiple times is safe (idempotent).
 *
 * <h2>Design rationale</h2>
 * <p>Expiry sweeping is decoupled from the publish hot path to avoid any overhead
 * on event delivery. The watchdog operates on a {@link Consumer} sweep action
 * provided by the publisher, keeping the watchdog independent of internal data
 * structures. Sweep failures are logged but never propagated — the watchdog
 * continues its schedule regardless.
 *
 * @since 0.2.0
 */
final class InqConsumerExpiryWatchdog implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(InqConsumerExpiryWatchdog.class);

  private final Duration interval;
  private final Consumer<DefaultInqEventPublisher> sweepAction;
  // Captured at construction for logging after owner may have been GC'd
  private final String ownerName;
  // Holds a weak reference so the owner can be garbage collected
  private final WeakReference<DefaultInqEventPublisher> ownerRef;
  private volatile Thread watchdogThread;
  /**
   * Flag to signal the watchdog to stop. Volatile ensures visibility across
   * the owning thread and the virtual thread without additional synchronization.
   */
  private volatile boolean running = true;

  /**
   * Creates a new expiry watchdog. The thread is not started until
   * {@link #startThread()} is called explicitly.
   *
   * @param owner       the owning publisher (used for naming, logging, and sweep target)
   * @param interval    the interval between sweep cycles (must be positive)
   * @param sweepAction the action to execute on each sweep cycle — typically a method
   *                    reference to the publisher's internal sweep logic
   * @throws IllegalArgumentException if interval is zero or negative
   */
  InqConsumerExpiryWatchdog(DefaultInqEventPublisher owner,
                            Duration interval,
                            Consumer<DefaultInqEventPublisher> sweepAction) {
    Objects.requireNonNull(owner, "owner must not be null");
    Objects.requireNonNull(interval, "interval must not be null");
    Objects.requireNonNull(sweepAction, "sweepAction must not be null");
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException("interval must be positive, was: " + interval);
    }

    this.interval = interval;
    this.sweepAction = sweepAction;
    this.ownerName = owner.elementName();
    this.ownerRef = new WeakReference<>(owner);
  }

  /**
   * Starts the virtual background thread.
   * May only be called if the watchdog has been successfully registered via CAS.
   */
  void startThread() {
    if (this.watchdogThread != null) {
      return; // Already started (idempotent)
    }

    // Virtual threads are daemon threads by default — they will not prevent
    // JVM shutdown even if close() is never called.
    this.watchdogThread = Thread.ofVirtual()
        .name("inq-expiry-watchdog-" + ownerName)
        .start(this::watchdogLoop);

    LOGGER.debug("Expiry watchdog started for '{}' with interval {}", ownerName, interval);
  }

  /**
   * The main watchdog loop. Sleeps for the configured interval, then executes
   * the sweep action. Repeats until {@link #close()} is called or the thread
   * is interrupted.
   */
  private void watchdogLoop() {
    while (running) {
      try {
        Thread.sleep(interval);
      } catch (InterruptedException e) {
        // close() was called or the thread was interrupted externally — exit gracefully
        Thread.currentThread().interrupt();
        return;
      }

      // Re-check after waking up — close() may have been called during sleep
      if (!running) {
        return;
      }

      DefaultInqEventPublisher owner = ownerRef.get();
      if (owner == null) {
        LOGGER.debug("Owner of expiry watchdog '{}' was garbage collected. Stopping virtual thread.", ownerName);
        running = false;
        return;
      }

      try {
        sweepAction.accept(owner);
      } catch (Throwable t) {
        // Never let a sweep failure kill the watchdog — log and continue
        LOGGER.warn("Expiry sweep failed — watchdog continues", t);
      }
    }
  }

  /**
   * Returns {@code true} if the watchdog is still running.
   */
  boolean isRunning() {
    Thread t = watchdogThread;
    return running && t != null && t.isAlive();
  }

  /**
   * Stops the watchdog gracefully.
   *
   * <p>Sets the running flag to {@code false} and interrupts the virtual thread.
   * If the thread is currently sleeping, it wakes up immediately and exits.
   * If it is executing a sweep, the sweep completes before the thread terminates.
   *
   * <p>This method is idempotent — calling it multiple times has no effect
   * after the first call.
   */
  @Override
  public void close() {
    if (!running) {
      return;
    }
    running = false;

    Thread t = watchdogThread;
    if (t != null) {
      t.interrupt();
      LOGGER.debug("Expiry watchdog stopped (thread: {})", t.getName());
    } else {
      // Thread was never started (e.g. due to discarded CAS)
      LOGGER.debug("Expiry watchdog closed before thread was started");
    }
  }
}
