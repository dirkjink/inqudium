package eu.inqudium.core.pipeline;

/**
 * Marker interface for objects decorated by an {@link InqPipeline}.
 *
 * <p>Both proxy-based and supplier-based pipeline decorations implement this
 * interface, enabling runtime introspection of the pipeline composition:
 * <pre>{@code
 * PaymentApi resilient = InqPipeline.of(service, PaymentApi.class)
 *     .shield(circuitBreaker)
 *     .shield(retry)
 *     .decorate();
 *
 * // Introspect via instanceof
 * if (resilient instanceof InqPipelineProxy proxy) {
 *     InqPipelineInfo info = proxy.getPipelineInfo();
 *     info.decorators();            // sorted chain
 *     info.toChainDescription();    // "CircuitBreaker 'x' → Retry 'y'"
 *     info.findDecorator(CircuitBreaker.class).ifPresent(cb ->
 *         log.info("State: {}", cb.getState()));
 * }
 *
 * // Also works for Supplier-based pipelines
 * Supplier<r> supplier = InqPipeline.of(() -> service.call())
 *     .shield(circuitBreaker)
 *     .decorate();
 *
 * if (supplier instanceof InqPipelineProxy proxy) {
 *     // same introspection API
 * }
 * }</pre>
 *
 * @since 0.1.0
 */
public interface InqPipelineProxy {

  /**
   * Returns the pipeline composition metadata.
   *
   * @return the immutable pipeline info
   */
  InqPipelineInfo getPipelineInfo();
}
