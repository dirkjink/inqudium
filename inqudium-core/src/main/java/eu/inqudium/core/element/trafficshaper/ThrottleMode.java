package eu.inqudium.core.element.trafficshaper;

/**
 * Defines how the traffic shaper handles requests that exceed capacity.
 *
 * <p>Both modes delay requests to smooth traffic. They differ only
 * in what happens when the internal scheduling queue is full.
 */
public enum ThrottleMode {

  /**
   * Always delay requests to shape traffic evenly. If the maximum
   * queue depth or wait duration is exceeded, reject the request.
   * This is the default and most common mode.
   */
  SHAPE_AND_REJECT_OVERFLOW,

  /**
   * Delay requests to shape traffic, but never reject. Requests
   * that would exceed the queue depth are still admitted — the
   * queue depth limit becomes advisory rather than enforced.
   * <p>Use with caution: under sustained overload, wait times
   * grow unbounded.
   */
  SHAPE_UNBOUNDED
}
