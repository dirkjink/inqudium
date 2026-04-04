package eu.inqudium.core.pipeline;

/**
 * Package-private holder for the shared pass-through singleton.
 * Separated into its own class to keep the {@link LayerAction} interface clean.
 */
enum PassThrough implements LayerAction<Object, Object> {
  INSTANCE;

  @Override
  public Object execute(long chainId, long callId, Object argument, InternalExecutor<Object, Object> next) {
    return next.execute(chainId, callId, argument);
  }
}

/**
 * Functional interface defining the behavior of a single layer in the wrapper chain.
 *
 * <p>A {@code LayerAction} has <strong>around-semantics</strong>, similar to a Servlet Filter
 * or Spring AOP {@code @Around} advice. It receives the chain and call identifiers, the
 * argument, and a reference to the {@code next} step in the chain. The action decides
 * <strong>when, whether, and how</strong> to invoke the next step:</p>
 *
 * <h3>Pre-processing only (fire-and-forget logging)</h3>
 * <pre>{@code
 * (chainId, callId, arg, next) -> {
 *     log.info("[chain={}, call={}] entering", chainId, callId);
 *     return next.execute(chainId, callId, arg);
 * }
 * }</pre>
 *
 * <h3>Pre- and post-processing (timing)</h3>
 * <pre>{@code
 * (chainId, callId, arg, next) -> {
 *     long start = System.nanoTime();
 *     R result = next.execute(chainId, callId, arg);
 *     metrics.record(System.nanoTime() - start);
 *     return result;
 * }
 * }</pre>
 *
 * <h3>Exception handling (resilience)</h3>
 * <pre>{@code
 * (chainId, callId, arg, next) -> {
 *     try {
 *         return next.execute(chainId, callId, arg);
 *     } catch (Exception e) {
 *         return fallbackValue;
 *     }
 * }
 * }</pre>
 *
 * <h3>Conditional execution (caching)</h3>
 * <pre>{@code
 * (chainId, callId, arg, next) -> {
 *     if (cache.containsKey(arg)) return cache.get(arg);
 *     R result = next.execute(chainId, callId, arg);
 *     cache.put(arg, result);
 *     return result;
 * }
 * }</pre>
 *
 * <h3>Pass-through (no-op)</h3>
 * <p>When no custom behavior is needed, use {@link #passThrough()} to forward directly
 * to the next step without any overhead beyond the method call itself.</p>
 *
 * @param <A> the argument type flowing through the chain
 * @param <R> the return type flowing back through the chain
 */
@FunctionalInterface
public interface LayerAction<A, R> {

  /**
   * Returns a pass-through action that simply forwards to the next step.
   * Uses a shared singleton instance to avoid unnecessary lambda allocations.
   *
   * @param <A> the argument type
   * @param <R> the return type
   * @return a no-op layer action
   */
  @SuppressWarnings("unchecked")
  static <A, R> LayerAction<A, R> passThrough() {
    return (LayerAction<A, R>) PassThrough.INSTANCE;
  }

  /**
   * Executes this layer's logic, deciding when and whether to invoke the next step.
   *
   * @param chainId  identifies the wrapper chain (shared across all layers)
   * @param callId   identifies this particular invocation (unique per call)
   * @param argument the argument flowing through the chain
   * @param next     the next step in the chain — call {@code next.execute(...)} to proceed
   * @return the result, either from the next step or produced/modified by this layer
   */
  R execute(long chainId, long callId, A argument, InternalExecutor<A, R> next);
}
