package eu.inqudium.bulkhead.imperative;

import eu.inqudium.core.bulkhead.BulkheadConfig;
import eu.inqudium.core.bulkhead.BulkheadStateMachine;

/**
 * Factory for creating configured instances of imperative bulkheads.
 *
 * <p>It evaluates the provided {@link BulkheadConfig} to determine whether to wire
 * the facade with a static, semaphore-based state machine or an adaptive,
 * lock-based state machine powered by a limit algorithm.
 *
 * @since 0.2.0
 */
public final class ImperativeBulkheadFactory {

  private ImperativeBulkheadFactory() {
    // Prevent instantiation of utility class
  }

  /**
   * Creates a new imperative bulkhead based on the provided configuration.
   *
   * @param name   the unique name of the bulkhead instance
   * @param config the configuration dictating the behavior and limits
   * @return a fully wired imperative bulkhead ready for decoration
   */
  public static ImperativeBulkhead create(String name, BulkheadConfig config) {
    BulkheadStateMachine stateMachine;

    if (config.getLimitAlgorithm() != null) {
      // Dynamic: Adaptive limits configured (e.g. AIMD, Vegas)
      stateMachine = new AdaptiveImperativeStateMachine(name, config, config.getLimitAlgorithm());
    } else {
      // Static: Fixed limits using standard Semaphores
      stateMachine = new ImperativeBulkheadStateMachine(name, config);
    }

    return new ImperativeBulkhead(name, config, stateMachine);
  }
}