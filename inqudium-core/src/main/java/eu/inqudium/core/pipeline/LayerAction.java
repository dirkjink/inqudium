package eu.inqudium.core.pipeline;

/**
 * Package-private holder for the shared pass-through singleton.
 *
 * <p>Separated into its own enum class to keep the {@link LayerAction} interface
 * clean and to guarantee a single JVM-wide instance via the enum pattern.
 * The singleton is accessed through {@link LayerAction#passThrough()}.</p>
 *
 * <p>Using an enum instead of a static final field ensures thread-safe
 * lazy initialization by the JVM class loader and prevents multiple
 * instantiations even under reflection or serialization attacks.</p>
 */
enum PassThrough implements LayerAction<Object, Object> {
  /** The sole instance — simply forwards to the next step without any logic. */
  INSTANCE;

  /**
   * Forwards the call directly to the next step in the chain.
   * No pre-processing, no post-processing, no exception handling —
   * this is a true no-op layer.
   */
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
 * <strong>when, whether, and how</strong> to invoke the next step.</p>
 *
 * <p>This is the most fundamental abstraction in the pipeline framework. Both
 * {@link InqDecorator} (deferred wrapping) and {@code InqExecutor} (immediate
 * execution) extend this interface, inheriting its around-advice contract.</p>
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
   *
   * <p>Uses a shared singleton instance ({@link PassThrough#INSTANCE}) to avoid
   * unnecessary lambda allocations. The unchecked cast is safe because the
   * pass-through implementation is fully generic — it never inspects or
   * modifies the argument or return value.</p>
   *
   * @param <A> the argument type (inferred from context)
   * @param <R> the return type (inferred from context)
   * @return a no-op layer action that delegates directly to the next step
   */
  @SuppressWarnings("unchecked")
  static <A, R> LayerAction<A, R> passThrough() {
    return (LayerAction<A, R>) PassThrough.INSTANCE;
  }

  /**
   * Executes this layer's logic, deciding when and whether to invoke the next step.
   *
   * <p>The implementation has full control over the invocation flow:</p>
   * <ul>
   *   <li>Call {@code next.execute(chainId, callId, argument)} to proceed down the chain</li>
   *   <li>Skip the call to short-circuit (e.g. return a cached value)</li>
   *   <li>Call it multiple times for retry logic</li>
   *   <li>Wrap it in try/catch for exception handling</li>
   *   <li>Modify the argument before or the result after the call</li>
   * </ul>
   *
   * @param chainId  identifies the wrapper chain (shared across all layers in the same chain)
   * @param callId   identifies this particular invocation (unique per call, monotonically increasing)
   * @param argument the argument flowing through the chain ({@code null} for void-argument wrappers)
   * @param next     the next step in the chain — call {@code next.execute(...)} to proceed
   * @return the result, either from the next step or produced/modified by this layer
   */
  R execute(long chainId, long callId, A argument, InternalExecutor<A, R> next);
}
