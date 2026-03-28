package eu.inqudium.core.exception;

import eu.inqudium.core.InqElementType;

import java.time.Duration;

/**
 * Thrown when a rate limiter denies a request because no permits are available
 * and the wait timeout (if any) has expired.
 *
 * <p>No call was made to the downstream service — the rate limiter rejected
 * the request to protect throughput limits (ADR-009, ADR-019).
 *
 * @since 0.1.0
 */
public class InqRequestNotPermittedException extends InqException {

  private final Duration waitEstimate;

  /**
   * Creates a new exception indicating that the rate limiter denied the request.
   *
   * @param elementName  the rate limiter instance name
   * @param waitEstimate estimated duration until the next permit becomes available
   */
  public InqRequestNotPermittedException(String elementName, Duration waitEstimate) {
    super(elementName, InqElementType.RATE_LIMITER,
        String.format("RateLimiter '%s' denied request (next permit in ~%dms)", elementName, waitEstimate.toMillis()));
    this.waitEstimate = waitEstimate;
  }

  /**
   * Returns the estimated wait time until the next permit.
   *
   * @return the estimated wait duration
   */
  public Duration getWaitEstimate() {
    return waitEstimate;
  }
}
