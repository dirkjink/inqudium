package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.Throws;

import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * Dispatch extension for synchronous method calls.
 *
 * <p>Acts as the catch-all fallback — {@link #canHandle} always returns {@code true}.
 * When composing extensions in a {@link ProxyWrapper}, this extension should be
 * registered <strong>last</strong> so that more specific extensions get first match.</p>
 *
 * <h3>Chain-walk optimisation</h3>
 * <p>When {@link #linkInner} finds a type-compatible {@code SyncDispatchExtension}
 * in the inner proxy, the two extensions are wired into a direct chain walk that
 * bypasses proxy re-entry. The terminal then invokes the deep {@code realTarget}
 * directly.</p>
 *
 * <p>When no type-compatible counterpart exists, the extension is left unlinked.
 * At dispatch time it receives the <em>proxy target</em> (the JDK proxy of the
 * inner layer) from {@link ProxyWrapper}, ensuring that the inner proxy's
 * extension pipeline is still invoked correctly.</p>
 *
 * @since 0.5.0
 */
public class SyncDispatchExtension implements DispatchExtension {

  private final LayerAction<Void, Object> action;

  private final Function<InternalExecutor<Void, Object>,
      InternalExecutor<Void, Object>> nextStepFactory;

  /**
   * When non-null, overrides the target passed to {@link #dispatch} for
   * the terminal invocation. Set only when this extension was successfully
   * linked with an inner {@code SyncDispatchExtension} — the chain walk
   * handles intermediate layers, so the terminal can jump straight to the
   * deep real target.
   */
  private final Object overrideTarget;

  /**
   * Per-extension handle cache — avoids a global singleton.
   */
  private final MethodHandleCache handleCache;

  // ======================== Public constructor (root / standalone) ========================

  public SyncDispatchExtension(LayerAction<Void, Object> action) {
    this.action = action;
    this.nextStepFactory = Function.identity();
    this.overrideTarget = null;
    this.handleCache = new MethodHandleCache();
  }

  // ======================== Internal constructors ========================

  /**
   * Linked constructor — wires a direct chain walk to the inner extension
   * and uses {@code realTarget} as terminal override. Inherits the outer
   * extension's handle cache so that already-resolved handles are reused.
   */
  private SyncDispatchExtension(LayerAction<Void, Object> action,
                                SyncDispatchExtension inner,
                                Object realTarget,
                                MethodHandleCache handleCache) {
    this.action = action;
    this.nextStepFactory = (inner != null)
        ? terminal -> (cid, caid, a) -> inner.executeChain(cid, caid, terminal)
        : Function.identity();
    this.overrideTarget = realTarget;
    this.handleCache = handleCache;
  }

  // ======================== Helpers ========================

  private static SyncDispatchExtension findInner(DispatchExtension[] extensions) {
    for (int i = 0; i < extensions.length; i++) {
      if (extensions[i] instanceof SyncDispatchExtension sync) {
        return sync;
      }
    }
    return null;
  }

  private InternalExecutor<Void, Object> buildTerminal(Method method,
                                                       Object[] args,
                                                       Object target) {
    // Eagerly resolve so the handle is cached before the first hot-path call
    handleCache.resolve(method);
    return (chainId, callId, arg) -> {
      try {
        return handleCache.invoke(target, method, args);
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Throwable e) {
        throw Throws.rethrow(e);
      }
    };
  }

  // ======================== DispatchExtension SPI ========================

  @Override
  public boolean canHandle(Method method) {
    return true;
  }

  @Override
  public boolean isCatchAll() {
    return true;
  }

  /**
   * Dispatches the call through the action chain.
   *
   * <p>If this extension was linked (has an {@code overrideTarget}), the
   * terminal invokes the deep real target directly — the chain walk already
   * covers intermediate layers. Otherwise, the terminal invokes whatever
   * {@code target} the caller (typically {@link ProxyWrapper}) provides,
   * which is the delegate proxy for correct composition.</p>
   */
  @Override
  public Object dispatch(long chainId, long callId,
                         Method method, Object[] args, Object target) {
    Object effectiveTarget = (overrideTarget != null) ? overrideTarget : target;
    return executeChain(chainId, callId, buildTerminal(method, args, effectiveTarget));
  }

  // ======================== Chain walk ========================

  /**
   * Links with a type-compatible inner extension for optimised chain walk.
   *
   * <p>If a matching {@code SyncDispatchExtension} is found, a new instance
   * is returned that chains directly into it, using {@code realTarget} as
   * the terminal invocation target. The new instance inherits this
   * extension's handle cache. If no match is found, a fresh standalone
   * instance is returned.</p>
   */
  @Override
  public DispatchExtension linkInner(DispatchExtension[] innerExtensions,
                                     Object realTarget) {
    SyncDispatchExtension inner = findInner(innerExtensions);
    if (inner != null) {
      return new SyncDispatchExtension(this.action, inner, realTarget, this.handleCache);
    }
    return new SyncDispatchExtension(this.action);
  }

  // ======================== Internal ========================

  Object executeChain(long chainId, long callId, InternalExecutor<Void, Object> terminal) {
    return action.execute(chainId, callId, null, nextStepFactory.apply(terminal));
  }
}
