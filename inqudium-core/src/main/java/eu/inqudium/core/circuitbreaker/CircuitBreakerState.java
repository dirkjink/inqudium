package eu.inqudium.core.circuitbreaker;

/**
 * The three states of a circuit breaker state machine.
 *
 * <p>State transitions follow the standard pattern:
 * <ul>
 *   <li>{@code CLOSED} → {@code OPEN}: failure rate or slow call rate exceeds threshold.</li>
 *   <li>{@code OPEN} → {@code HALF_OPEN}: wait duration in open state has elapsed.</li>
 *   <li>{@code HALF_OPEN} → {@code CLOSED}: permitted probe calls succeed.</li>
 *   <li>{@code HALF_OPEN} → {@code OPEN}: permitted probe calls fail.</li>
 * </ul>
 *
 * <p>The sliding window (ADR-016) computes the failure rate; the behavioral contract
 * (ADR-005) decides the transitions; the paradigm module synchronizes the state
 * changes using its native concurrency primitives (ADR-004).
 *
 * @since 0.1.0
 */
public enum CircuitBreakerState {

  /**
   * Normal operation. Calls pass through and outcomes are recorded in the sliding window.
   */
  CLOSED,

  /**
   * Calls are rejected immediately with {@link eu.inqudium.core.exception.InqCallNotPermittedException}.
   * No call reaches the downstream service. After the configured wait duration,
   * the breaker transitions to {@link #HALF_OPEN}.
   */
  OPEN,

  /**
   * A limited number of probe calls are permitted to test whether the downstream
   * service has recovered. If the probes succeed, the breaker transitions to
   * {@link #CLOSED}. If they fail, it returns to {@link #OPEN}.
   */
  HALF_OPEN
}
