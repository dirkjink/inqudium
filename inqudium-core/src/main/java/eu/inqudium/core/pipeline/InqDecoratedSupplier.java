package eu.inqudium.core.pipeline;

import java.util.function.Supplier;

/**
 * A {@link Supplier} that also carries pipeline metadata via {@link InqPipelineProxy}.
 *
 * <p>Returned by {@link InqPipeline.Builder#decorate()} so that supplier-based
 * pipelines support the same introspection API as proxy-based pipelines:
 * <pre>{@code
 * Supplier<Payment> resilient = InqPipeline.of(() -> service.charge(order))
 *     .shield(circuitBreaker)
 *     .shield(retry)
 *     .decorate();
 *
 * // Use as a normal Supplier
 * Payment result = resilient.get();
 *
 * // Introspect the pipeline
 * if (resilient instanceof InqPipelineProxy proxy) {
 *     proxy.getPipelineInfo().toChainDescription();
 * }
 * }</pre>
 *
 * @param <T> the result type
 * @since 0.1.0
 */
public final class InqDecoratedSupplier<T> implements Supplier<T>, InqPipelineProxy {

  private final Supplier<T> delegate;
  private final InqPipelineInfo info;

  InqDecoratedSupplier(Supplier<T> delegate, InqPipelineInfo info) {
    this.delegate = delegate;
    this.info = info;
  }

  @Override
  public T get() {
    return delegate.get();
  }

  @Override
  public InqPipelineInfo getPipelineInfo() {
    return info;
  }

  @Override
  public String toString() {
    return "InqDecoratedSupplier[" + info.toChainDescription() + "]";
  }
}
