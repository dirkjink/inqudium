package eu.inqudium.core.element.trafficshaper;

import java.time.Duration;

/**
 * Exception thrown when the traffic shaper rejects a request because
 * the scheduling queue is full or the required wait would exceed the
 * configured maximum.
 */
public class TrafficShaperException extends RuntimeException {

  private final String trafficShaperName;
  private final String instanceId;
  private final Duration wouldHaveWaited;
  private final int queueDepth;

  public TrafficShaperException(String trafficShaperName, Duration wouldHaveWaited, int queueDepth) {
    this(trafficShaperName, null, wouldHaveWaited, queueDepth);
  }

  /**
   * Fix 5: Constructor that includes the instance identifier for identity-based
   * comparison in fallback wrappers.
   *
   * @param trafficShaperName human-readable name
   * @param instanceId        unique instance identifier (UUID-based)
   * @param wouldHaveWaited   how long the request would have waited
   * @param queueDepth        queue depth at the time of rejection
   */
  public TrafficShaperException(
      String trafficShaperName,
      String instanceId,
      Duration wouldHaveWaited,
      int queueDepth) {
    super("TrafficShaper '%s' — request rejected, queue depth %d, would have waited %s ms"
        .formatted(trafficShaperName, queueDepth, wouldHaveWaited.toMillis()));
    this.trafficShaperName = trafficShaperName;
    this.instanceId = instanceId;
    this.wouldHaveWaited = wouldHaveWaited;
    this.queueDepth = queueDepth;
  }

  public String getTrafficShaperName() {
    return trafficShaperName;
  }

  /**
   * Fix 5: Returns the unique instance identifier of the traffic shaper
   * that produced this exception, or {@code null} if not set.
   */
  public String getInstanceId() {
    return instanceId;
  }

  /**
   * Returns how long the request would have needed to wait
   * if it had been admitted.
   */
  public Duration getWouldHaveWaited() {
    return wouldHaveWaited;
  }

  /**
   * Returns the queue depth at the time of rejection.
   */
  public int getQueueDepth() {
    return queueDepth;
  }
}
