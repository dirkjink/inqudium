package eu.inqudium.imperative.circuitbreaker;

import eu.inqudium.core.config.InqConfig;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqExecutor;
import eu.inqudium.imperative.bulkhead.config.InqImperativeBulkheadConfig;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import eu.inqudium.imperative.core.pipeline.InqAsyncExecutor;

public interface CircuitBreaker<A, R>
    extends InqDecorator<A, R>,
    InqExecutor<A, R>,
    InqAsyncExecutor<A, R>,
    InqAsyncDecorator<A, R> {

  /**
   * Creates a bulkhead from a general {@link InqConfig} container.
   *
   * @param config the configuration container holding an {@link InqImperativeBulkheadConfig}
   * @param <A>    the argument type
   * @param <R>    the return type
   * @return a new bulkhead instance
   */
  static <A, R> CircuitBreaker<A, R> of(InqConfig config) {
    return null;//of(config.of(InqImperativeCircuitBreakerConfig.class).orElseThrow());
  }

}
