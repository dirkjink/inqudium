package eu.inqudium.core.pipeline.proxy;

import eu.inqudium.core.pipeline.Wrapper;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Invocation handler that dispatches service methods through composable
 * {@link DispatchExtension} layers.
 *
 * <p>{@code ProxyWrapper} itself contains no sync/async/reactive logic — all
 * dispatch modes are pluggable extensions. This separation allows the proxy
 * infrastructure to remain stable while new dispatch strategies are added
 * simply by implementing {@link DispatchExtension}.</p>
 *
 * <h3>Extension dispatch</h3>
 * <p>Extensions are stored as a plain array (no {@code List} wrapper, no iterator
 * allocation). The dispatch loop on the hot path is a simple indexed {@code for}
 * that checks each extension's {@link DispatchExtension#canHandle} in registration
 * order. The first match receives the call. A catch-all extension at the end
 * guarantees that every method is handled.</p>
 *
 * <h3>Chain correctness</h3>
 * <p>The dispatch target passed to extensions is the <em>proxy target</em> —
 * the JDK proxy object (or real object) that this wrapper was built around.
 * Extensions that successfully link with a type-compatible inner extension
 * override this target with the deep {@code realTarget} for a zero-reentry
 * chain walk. Extensions that do <em>not</em> find a match invoke the proxy
 * target, which re-enters the inner proxy and correctly dispatches through
 * its extensions.</p>
 *
 * <h3>Construction-time validation</h3>
 * <p>The factory method {@link #createProxy} validates the extension array
 * eagerly, ensuring that:</p>
 * <ul>
 *   <li>At least one extension is provided.</li>
 *   <li>No extensions are null.</li>
 *   <li>No duplicate instances exist (same object reference).</li>
 *   <li>Exactly one catch-all extension is present, and it is last.</li>
 * </ul>
 * <p>This fail-fast approach surfaces configuration errors immediately rather
 * than deferring them to the first method call.</p>
 *
 * @since 0.5.0
 */
public class ProxyWrapper extends AbstractProxyWrapper {

    /**
     * Reusable empty array constant to avoid allocation.
     */
    private static final DispatchExtension[] EMPTY = new DispatchExtension[0];

    /**
     * The dispatch extensions for this proxy, in registration order.
     * May differ from the extensions passed to the constructor if linking
     * with inner extensions produced new instances.
     */
    private final DispatchExtension[] extensions;

    /**
     * The original target object (JDK proxy or real object) passed to
     * {@link #createProxy}. Used as the default terminal invocation target
     * when dispatching through extensions.
     *
     * <p>For the innermost proxy this equals the real target. For outer proxies
     * it is the inner JDK proxy, ensuring that unlinked extensions still
     * dispatch through the inner proxy's extension pipeline.</p>
     */
    private final Object proxyTarget;

    // ======================== Constructors ========================

    /**
     * Internal constructor — creates a new proxy wrapper with the given extensions.
     *
     * <p>If the delegate is a {@code ProxyWrapper} (stacked proxies), the
     * constructor attempts to link each extension with its type-compatible
     * counterpart in the inner proxy via {@link DispatchExtension#linkInner}.
     * This creates the chain-walk optimization that bypasses proxy re-entry.</p>
     *
     * <p>If the delegate is not a proxy, the extensions are simply cloned
     * (defensive copy to prevent external mutation).</p>
     *
     * @param name        human-readable layer name
     * @param delegate    the unwrapped delegate (either another ProxyWrapper or the real target)
     * @param proxyTarget the original JDK proxy or real object for terminal dispatch
     * @param extensions  the dispatch extensions to use
     */
    protected ProxyWrapper(String name, Object delegate, Object proxyTarget,
                           DispatchExtension[] extensions) {
        super(name, delegate);
        this.proxyTarget = proxyTarget;

        // Attempt chain-walk linking if the delegate is itself a ProxyWrapper
        if (delegate instanceof ProxyWrapper inner) {
            DispatchExtension[] innerExts = inner.extensions;
            Object rt = realTarget(); // The deep non-proxy target for linked optimization
            DispatchExtension[] linked = new DispatchExtension[extensions.length];
            for (int i = 0; i < extensions.length; i++) {
                // Each extension gets a chance to link with a type-compatible
                // counterpart in the inner proxy. If it finds one, it returns a
                // new instance wired into the inner chain. If not, it returns
                // itself unchanged.
                linked[i] = extensions[i].linkInner(innerExts, rt);
            }
            this.extensions = linked;
        } else {
            // No inner proxy — just clone the extensions to prevent external mutation
            this.extensions = extensions.clone();
        }
    }

    // ======================== Accessors ========================

    /**
     * Factory method: creates a JDK dynamic proxy with the given extensions.
     *
     * <p>The proxy implements both the service interface and the {@link Wrapper}
     * interface, allowing chain introspection on any proxy instance.</p>
     *
     * <p>Extensions are checked in registration order — register more specific
     * extensions first and the catch-all (typically {@link SyncDispatchExtension})
     * last.</p>
     *
     * @param serviceInterface the interface to proxy (must be an interface, not a class)
     * @param target           the real implementation to wrap
     * @param name             human-readable name for this proxy layer
     * @param extensions       one or more dispatch extensions (last must be a catch-all)
     * @param <T>              the service interface type
     * @return a JDK proxy that implements both the service interface and {@link Wrapper}
     * @throws IllegalArgumentException if the type is not an interface or the extensions
     *                                  are invalid
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(Class<T> serviceInterface, T target, String name,
                                    DispatchExtension... extensions) {
        // Validate that the target type is an interface (JDK proxies require this)
        validateInterface(serviceInterface);
        // Validate the extension array structure (nulls, duplicates, catch-all position)
        validateExtensions(extensions);

        // Check if the target is itself a proxy — if so, extract the inner handler
        // for chain structure inheritance in the AbstractBaseWrapper constructor
        AbstractProxyWrapper inner = resolveInner(target);
        Object delegate = (inner != null) ? inner : target;

        // Create the invocation handler and build the JDK proxy.
        // The proxy implements both the service interface (for business methods)
        // and the Wrapper interface (for chain introspection).
        ProxyWrapper handler = new ProxyWrapper(name, delegate, target, extensions);
        return (T) Proxy.newProxyInstance(
                serviceInterface.getClassLoader(),
                new Class<?>[]{serviceInterface, Wrapper.class},
                handler);
    }

    /**
     * Validates the extension array at construction time.
     *
     * <p>Performs the following checks in order:</p>
     * <ol>
     *   <li>The array must be non-null and non-empty.</li>
     *   <li>No individual extension may be null.</li>
     *   <li>No duplicate instances (same object reference at different indices) —
     *       duplicates would cause double dispatch for matching methods.</li>
     *   <li>No catch-all extension may appear before the last position —
     *       any extensions after a catch-all would be unreachable ("shadowed").</li>
     *   <li>The last extension must be a catch-all — this guarantees that every
     *       method can be dispatched.</li>
     * </ol>
     *
     * @param extensions the extension array to validate
     * @throws IllegalArgumentException if any validation rule is violated
     */
    private static void validateExtensions(DispatchExtension[] extensions) {
        // Rule 1: At least one extension is required
        if (extensions == null || extensions.length == 0) {
            throw new IllegalArgumentException(
                    "At least one DispatchExtension is required. "
                            + "Register a catch-all (e.g. SyncDispatchExtension) to handle all methods.");
        }

        for (int i = 0; i < extensions.length; i++) {
            // Rule 2: No null extensions
            if (extensions[i] == null) {
                throw new IllegalArgumentException(
                        "DispatchExtension at index " + i + " must not be null.");
            }

            // Rule 3: No duplicate instances — the same extension instance registered
            // twice would cause double dispatch for every matching method
            for (int j = 0; j < i; j++) {
                if (extensions[i] == extensions[j]) {
                    throw new IllegalArgumentException(
                            "Duplicate DispatchExtension instance at index " + i
                                    + " (same instance as index " + j + "). "
                                    + "Each extension must be a distinct instance.");
                }
            }

            // Rule 4: A catch-all before the last position means subsequent extensions
            // are unreachable — fail immediately with a clear error message
            if (extensions[i].isCatchAll() && i < extensions.length - 1) {
                throw new IllegalArgumentException(
                        "Invalid extension chain: Catch-all extension "
                                + extensions[i].getClass().getSimpleName()
                                + " found at index " + i + ", but must be the last extension. "
                                + "Extensions after a catch-all are unreachable.");
            }
        }

        // Rule 5: The last extension must be a catch-all.
        // Since we already ruled out catch-alls appearing earlier (rule 4),
        // this guarantees exactly one catch-all in the correct position.
        if (!extensions[extensions.length - 1].isCatchAll()) {
            throw new IllegalArgumentException(
                    "No valid catch-all DispatchExtension found at the end of the chain. "
                            + "Register a catch-all (e.g. SyncDispatchExtension) as the last extension "
                            + "to ensure every method can be dispatched.");
        }
    }

    // ======================== Factory methods ========================

    /**
     * Returns the extensions on this handler.
     *
     * <p>Package-private — used during inner-resolution when constructing
     * outer proxies that wrap this one, to enable chain-walk linking.</p>
     *
     * @return the dispatch extension array (not cloned — callers must not modify)
     */
    DispatchExtension[] extensions() {
        return extensions;
    }

    // ======================== Dispatch ========================

    /**
     * Dispatches the service method through the first matching extension.
     *
     * <p>Iterates through the extension array in registration order and delegates
     * to the first extension whose {@link DispatchExtension#canHandle} returns
     * {@code true}. Because the last extension is always a catch-all, this loop
     * is guaranteed to find a match.</p>
     *
     * <p>The invocation target passed to the extension is {@link #proxyTarget} —
     * the JDK proxy (or real object) this wrapper was constructed around.
     * Extensions that have been linked via {@link DispatchExtension#linkInner}
     * override this with the deep real target for their optimized chain walk;
     * all other extensions invoke the proxy target, preserving correct
     * composition across heterogeneous extension types.</p>
     *
     * @param method the service method being invoked
     * @param args   the method arguments
     * @return the method's return value
     * @throws Throwable                     if the dispatch or underlying method throws
     * @throws UnsupportedOperationException if no extension handles the method
     *                                       (should never happen with a properly validated extension array)
     */
    @Override
    protected Object dispatchServiceMethod(Method method, Object[] args) throws Throwable {
        // Generate IDs for this invocation — one CAS for the call ID
        long chainId = chainId();
        long callId = generateCallId();

        // Linear scan through extensions — first match wins.
        // The array is small (typically 1–3 extensions), so a plain loop
        // is faster than any map or polymorphic dispatch structure.
        for (int i = 0; i < extensions.length; i++) {
            DispatchExtension ext = extensions[i];
            if (ext.canHandle(method)) {
                return ext.dispatch(chainId, callId, method, args, proxyTarget);
            }
        }

        // This should never be reached if validateExtensions() was called,
        // because the catch-all extension handles every method.
        throw new UnsupportedOperationException(
                "No dispatch extension handles method: " + method
                        + " — ensure a catch-all extension (e.g. SyncDispatchExtension) is registered last.");
    }
}
