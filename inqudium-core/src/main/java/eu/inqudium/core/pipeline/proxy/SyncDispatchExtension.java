package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.pipeline.LayerAction;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * Dispatch extension for synchronous method calls.
 *
 * <p>This is the standard catch-all extension — {@link #canHandle} always returns
 * {@code true}, and {@link #isCatchAll()} returns {@code true}. When composing
 * extensions in a {@link ProxyWrapper}, this extension should be registered
 * <strong>last</strong> so that more specific extensions (e.g. async, reactive)
 * get first match.</p>
 *
 * <h3>Dispatch flow</h3>
 * <p>Each service method call is routed through the configured {@link LayerAction}
 * with around-semantics. The action receives a terminal {@link InternalExecutor}
 * lambda that invokes the method on the target via a cached {@link MethodHandle}.
 * The action decides when and whether to invoke the terminal step.</p>
 *
 * <h3>Chain-walk optimization</h3>
 * <p>When {@link #linkInner} finds a type-compatible {@code SyncDispatchExtension}
 * in the inner proxy, the two extensions are wired into a direct chain walk.
 * Instead of the outer extension invoking the inner proxy (which would re-enter
 * the JDK proxy machinery, walk through {@code invoke()}, classify the method,
 * and scan extensions again), the outer extension calls the inner extension's
 * {@link #executeChain} directly. The terminal step then invokes the deep
 * {@code realTarget} — the actual business object — bypassing all intermediate
 * proxy layers.</p>
 *
 * <p>When no type-compatible counterpart exists (e.g. the inner proxy has only
 * async extensions), the extension is left unlinked. At dispatch time it
 * receives the <em>proxy target</em> (the JDK proxy of the inner layer) from
 * {@link ProxyWrapper}, ensuring that the inner proxy's extension pipeline is
 * still invoked correctly through normal proxy re-entry.</p>
 *
 * @since 0.5.0
 */
public class SyncDispatchExtension implements DispatchExtension {

  /**
   * The around-advice applied to every dispatched method call.
   * Type parameters are {@code <Void, Object>} because proxy dispatch has
   * no typed argument (always null) and returns raw Object.
   */
  private final LayerAction<Void, Object> action;

  /**
   * Factory function that wraps the terminal executor with the inner chain's
   * logic (if linked). For unlinked extensions, this is {@code Function.identity()}
   * — the terminal is used as-is. For linked extensions, this replaces the
   * terminal with a lambda that calls the inner extension's {@code executeChain()},
   * effectively chaining the two actions together.
   */
  private final Function<InternalExecutor<Void, Object>,
      InternalExecutor<Void, Object>> nextStepFactory;

  /**
   * When non-null, overrides the target passed to {@link #dispatch} for the
   * terminal invocation. Set only when this extension was successfully linked
   * with an inner {@code SyncDispatchExtension} — the chain walk handles
   * intermediate layers, so the terminal can jump straight to the deep real
   * target without going through intermediate proxies.
   */
  private final Object overrideTarget;

  /**
   * Per-extension handle cache — avoids a global singleton and keeps cache
   * sizes proportional to the methods actually dispatched through this extension.
   * When extensions are linked, the outer extension's cache may be inherited
   * by the linked instance to reuse already-resolved handles.
   */
  private final MethodHandleCache handleCache;

  // ======================== Public constructor (root / standalone) ========================

  /**
   * Creates a new standalone (root) sync dispatch extension.
   *
   * <p>This is the constructor used by application code and by
   * {@link InqProxyFactory}. The extension starts unlinked — chain-walk
   * linking happens later during {@link ProxyWrapper} construction if
   * an inner proxy is detected.</p>
   *
   * @param action the around-advice to apply to every dispatched method call
   */
  public SyncDispatchExtension(LayerAction<Void, Object> action) {
    this.action = action;
    this.nextStepFactory = Function.identity(); // No inner chain — terminal is used as-is
    this.overrideTarget = null;                 // No override — use whatever target ProxyWrapper provides
    this.handleCache = new MethodHandleCache(); // Fresh cache for this extension
  }

  // ======================== Internal constructors ========================

  /**
   * Linked constructor — wires a direct chain walk to the inner extension
   * and uses {@code realTarget} as the terminal override.
   *
   * <p>Inherits the outer extension's handle cache so that already-resolved
   * handles are reused across the linked pair.</p>
   *
   * @param action      the around-advice for this (outer) extension
   * @param inner       the inner extension to chain into (or null if no match found)
   * @param realTarget  the deep non-proxy target for terminal invocation (or null if unlinked)
   * @param handleCache the handle cache to inherit from the outer extension
   */
  private SyncDispatchExtension(LayerAction<Void, Object> action,
                                SyncDispatchExtension inner,
                                Object realTarget,
                                MethodHandleCache handleCache) {
    this.action = action;

    // If an inner extension was found, create a next-step factory that chains
    // into the inner extension's executeChain(). This replaces normal proxy
    // re-entry with a direct method call.
    // If no inner was found, use identity — the terminal is used as-is.
    this.nextStepFactory = (inner != null)
        ? terminal -> (cid, caid, a) -> inner.executeChain(cid, caid, terminal)
        : Function.identity();

    this.overrideTarget = realTarget;
    this.handleCache = handleCache;
  }

  // ======================== Helpers ========================

  /**
   * Searches an extension array for the first {@code SyncDispatchExtension}.
   *
   * <p>Used during linking to find a type-compatible counterpart in the
   * inner proxy's extensions.</p>
   *
   * @param extensions the inner proxy's extension array
   * @return the first {@code SyncDispatchExtension} found, or {@code null}
   */
  private static SyncDispatchExtension findInner(DispatchExtension[] extensions) {
    for (int i = 0; i < extensions.length; i++) {
      if (extensions[i] instanceof SyncDispatchExtension sync) {
        return sync;
      }
    }
    return null;
  }

  /**
   * Builds the terminal executor lambda for a specific method invocation.
   *
   * <p>The terminal is the innermost step in the chain — it invokes the actual
   * method on the target via the cached {@link MethodHandle}. The handle is
   * eagerly resolved (cached) before the lambda is created, so the first
   * hot-path call doesn't pay the resolution cost.</p>
   *
   * <p>Exceptions from the method invocation are processed through
   * {@link DispatchExtension#handleException} to unwrap reflection artifacts
   * and classify the exception type.</p>
   *
   * @param method the service method to invoke
   * @param args   the method arguments
   * @param target the object to invoke the method on
   * @return an {@link InternalExecutor} that invokes the method when executed
   */
  private InternalExecutor<Void, Object> buildTerminal(Method method,
                                                       Object[] args,
                                                       Object target) {
    // Eagerly resolve the method handle so it's cached before the first call
    handleCache.resolve(method);
    return (chainId, callId, arg) -> {
      try {
        return handleCache.invoke(target, method, args);
      } catch (Throwable e) {
        // Unwrap reflection wrappers and classify the exception
        throw handleException(method, e);
      }
    };
  }

  // ======================== DispatchExtension SPI ========================

  /**
   * Always returns {@code true} — this extension handles every method.
   *
   * <p>As a catch-all, it must be registered last in the extension array
   * so that more specific extensions get first match.</p>
   */
  @Override
  public boolean canHandle(Method method) {
    return true;
  }

  /**
   * Returns {@code true} — marks this extension as the catch-all fallback.
   */
  @Override
  public boolean isCatchAll() {
    return true;
  }

  /**
   * Dispatches the method call through the action chain.
   *
   * <p>Determines the effective target for the terminal invocation:</p>
   * <ul>
   *   <li>If this extension was linked (has an {@code overrideTarget}), the
   *       terminal invokes the deep real target directly. The chain walk
   *       already covers intermediate layers.</li>
   *   <li>If this extension is unlinked ({@code overrideTarget} is null), the
   *       terminal invokes whatever target the caller (typically
   *       {@link ProxyWrapper}) provides — which is the delegate proxy for
   *       correct composition through the inner proxy's extension pipeline.</li>
   * </ul>
   *
   * @param chainId the chain identifier
   * @param callId  the per-call identifier
   * @param method  the service method
   * @param args    the method arguments
   * @param target  the default invocation target (from ProxyWrapper)
   * @return the method's return value
   */
  @Override
  public Object dispatch(long chainId, long callId,
                         Method method, Object[] args, Object target) {
    // Use the override target (deep real target) if linked, otherwise
    // fall back to the target provided by ProxyWrapper (the inner proxy)
    Object effectiveTarget = (overrideTarget != null) ? overrideTarget : target;
    return executeChain(chainId, callId, buildTerminal(method, args, effectiveTarget));
  }

  // ======================== Chain walk ========================

  /**
   * Links with a type-compatible inner extension for optimized chain walk.
   *
   * <p>Searches the inner proxy's extensions for another {@code SyncDispatchExtension}.
   * If found, returns a new linked instance that chains directly into the inner
   * extension (bypassing proxy re-entry) and uses {@code realTarget} as the
   * terminal invocation target. The new instance inherits this extension's
   * handle cache to avoid re-resolving already-cached handles.</p>
   *
   * <p>If no type-compatible inner extension is found, returns a fresh standalone
   * instance that preserves the existing handle cache but performs no chain-walk
   * optimization.</p>
   *
   * @param innerExtensions the extensions registered on the inner proxy
   * @param realTarget      the deepest non-proxy target
   * @return a (possibly new) extension instance, potentially linked into the inner chain
   */
  @Override
  public DispatchExtension linkInner(DispatchExtension[] innerExtensions,
                                     Object realTarget) {
    SyncDispatchExtension inner = findInner(innerExtensions);
    if (inner != null) {
      // Found a compatible inner extension — create a linked instance
      // that chains directly into it, bypassing proxy re-entry
      return new SyncDispatchExtension(this.action, inner, realTarget, this.handleCache);
    }
    // No type-compatible inner found — return a standalone instance that
    // preserves the existing handle cache to avoid re-resolving handles
    return new SyncDispatchExtension(this.action, null, null, this.handleCache);
  }

  // ======================== Internal ========================

  /**
   * Executes the action chain for a single invocation.
   *
   * <p>The {@code terminal} is the innermost step — the lambda that invokes
   * the actual method. The {@link #nextStepFactory} wraps this terminal with
   * the inner extension's chain (if linked), producing the complete next-step
   * reference that the action receives.</p>
   *
   * <p>This method is also called by the outer extension in a linked pair
   * (via the {@code nextStepFactory} lambda), creating the recursive chain walk.</p>
   *
   * @param chainId  the chain identifier
   * @param callId   the call identifier
   * @param terminal the terminal executor that invokes the actual method
   * @return the method's return value, potentially modified by the action
   */
  Object executeChain(long chainId, long callId, InternalExecutor<Void, Object> terminal) {
    // Apply the next-step factory: for linked extensions, this wraps the terminal
    // with the inner extension's executeChain(); for unlinked extensions, this
    // returns the terminal unchanged (identity function).
    return action.execute(chainId, callId, null, nextStepFactory.apply(terminal));
  }
}
