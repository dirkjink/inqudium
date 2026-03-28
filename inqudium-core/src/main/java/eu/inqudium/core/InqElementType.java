package eu.inqudium.core;

/**
 * Identifies the six resilience element kinds.
 *
 * <p>Used as a discriminator in events (ADR-003), exceptions (ADR-009),
 * context propagation (ADR-011), and pipeline ordering (ADR-017).
 * Every {@link InqElement} instance carries an {@code InqElementType}.
 *
 * @since 0.1.0
 */
public enum InqElementType {

  /**
   * Circuit breaker — cascading failure shield (element symbol: Cb).
   */
  CIRCUIT_BREAKER,

  /**
   * Retry — configurable backoff on transient failures (element symbol: Rt).
   */
  RETRY,

  /**
   * Rate limiter — throughput control via token bucket (element symbol: Rl).
   */
  RATE_LIMITER,

  /**
   * Bulkhead — failure isolation via concurrency limiting (element symbol: Bh).
   */
  BULKHEAD,

  /**
   * Time limiter — caller wait time bound, no thread interrupt (element symbol: Tl).
   */
  TIME_LIMITER,

  /**
   * Cache — response caching to reduce load (element symbol: Ca).
   */
  CACHE
}
