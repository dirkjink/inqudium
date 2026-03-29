package eu.inqudium.core.pipeline;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Immutable snapshot of a pipeline's composition — the decorators, their order,
 * the call ID generator, and (for proxy pipelines) the target and interface type.
 *
 * <p>Obtained via {@link InqPipelineProxy#getPipelineInfo()} on any pipeline-decorated
 * object (proxy or supplier).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * if (resilient instanceof InqPipelineProxy proxy) {
 *     InqPipelineInfo info = proxy.getPipelineInfo();
 *
 *     // Inspect the chain
 *     info.decorators().forEach(d ->
 *         log.info("{} '{}'", d.getElementType(), d.getName()));
 *
 *     // Find a specific element
 *     info.findDecorator(CircuitBreaker.class).ifPresent(cb ->
 *         log.info("CB state: {}", cb.getState()));
 *
 *     // Debug string
 *     log.debug("Chain: {}", info.toChainDescription());
 * }
 * }</pre>
 *
 * @param decorators      the decoration chain in execution order (outermost first)
 * @param order           the ordering strategy used
 * @param callIdGenerator the call ID generator
 * @param interfaceType   the service interface (null for supplier-based pipelines)
 * @param target          the service instance (null for supplier-based pipelines)
 * @since 0.1.0
 */
public record InqPipelineInfo(
    List<InqDecorator> decorators,
    PipelineOrder order,
    eu.inqudium.core.InqCallIdGenerator callIdGenerator,
    Class<?> interfaceType,
    Object target
) {

  /**
   * Finds the first decorator of the given type in the chain.
   *
   * <pre>{@code
   * Optional<CircuitBreaker> cb = info.findDecorator(CircuitBreaker.class);
   * cb.ifPresent(c -> log.info("State: {}", c.getState()));
   * }</pre>
   *
   * @param type the decorator type to find (e.g. CircuitBreaker.class)
   * @param <D>  the decorator type
   * @return the first matching decorator, or empty
   */
  @SuppressWarnings("unchecked")
  public <D extends InqDecorator> Optional<D> findDecorator(Class<D> type) {
    return decorators.stream()
        .filter(type::isInstance)
        .map(d -> (D) d)
        .findFirst();
  }

  /**
   * Finds all decorators of the given type in the chain.
   *
   * @param type the decorator type to find
   * @param <D>  the decorator type
   * @return all matching decorators in chain order
   */
  @SuppressWarnings("unchecked")
  public <D extends InqDecorator> List<D> findDecorators(Class<D> type) {
    return decorators.stream()
        .filter(type::isInstance)
        .map(d -> (D) d)
        .toList();
  }

  /**
   * Returns a human-readable description of the decoration chain.
   *
   * <p>Format: {@code "RateLimiter 'apiGateway' → Retry 'orderService' → CircuitBreaker 'paymentService'"}
   *
   * @return the chain description
   */
  public String toChainDescription() {
    if (decorators.isEmpty()) {
      return "(empty pipeline)";
    }
    return decorators.stream()
        .map(d -> String.format(Locale.ROOT, "%s '%s'", d.getElementType(), d.getName()))
        .collect(Collectors.joining(" → "));
  }

  @Override
  public String toString() {
    var chain = toChainDescription();
    if (interfaceType != null) {
      return "InqPipelineInfo[proxy=" + interfaceType.getSimpleName() + ", chain=" + chain + "]";
    }
    return "InqPipelineInfo[chain=" + chain + "]";
  }
}
