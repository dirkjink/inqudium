package eu.inqudium.core.trafficshaper.strategy;

import java.time.Duration;
import java.time.Instant;

/**
 * Base interface for the scheduling state of a traffic shaper strategy.
 *
 * <p>Each strategy implementation defines its own state record that
 * implements this interface. The imperative wrapper accesses the
 * cross-cutting fields ({@link #epoch()}, {@link #queueDepth()})
 * for reset-invalidation and monitoring without knowing the concrete type.
 *
 * <p>Implementations must be immutable — every mutating operation returns
 * a new instance. The imperative wrapper's CAS-based thread safety depends
 * on this guarantee.
 */
public interface SchedulingState {

  /**
   * Monotonically increasing generation counter. Incremented on reset
   * to invalidate pending reservations from a previous lifecycle.
   */
  long epoch();

  /**
   * Number of requests currently waiting for their scheduled slot.
   */
  int queueDepth();

  /**
   * Total number of requests admitted since creation.
   */
  long totalAdmitted();

  /**
   * Total number of requests rejected since creation.
   */
  long totalRejected();

  /**
   * Returns the projected wait time for the tail of the queue.
   * Used for unbounded queue monitoring.
   */
  Duration projectedTailWait(Instant now);
}
