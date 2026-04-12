package eu.inqudium.aspect.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.LayerAction;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Adapts any {@link InqElement} that implements {@link InqDecorator} to the
 * synchronous aspect pipeline as an {@link AspectLayerProvider}.
 *
 * <p>Works with every imperative resilience element (bulkhead, retry,
 * timeout, circuit-breaker, …) — the only requirement is that the element
 * implements both {@link InqElement} (for name and type) and
 * {@link InqDecorator InqDecorator&lt;Void, Object&gt;} (for the sync
 * around-advice).</p>
 *
 * <p>The constructor uses an intersection type bound to enforce this at
 * compile time. The layer name is derived automatically from the element's
 * {@link InqElement#getElementType() type} and
 * {@link InqElement#getName() name}, e.g. {@code "BULKHEAD(paymentService)"}
 * or {@code "RETRY(orderApi)"}.</p>
 *
 * <h3>Method filtering</h3>
 * <p>{@link #canHandle(Method)} excludes methods returning
 * {@link CompletionStage}. Synchronous around-advice releases resources
 * (permits, tokens, etc.) when the method <em>returns</em>, not when the
 * async operation <em>completes</em>. For async methods, use
 * {@link AsyncElementLayerProvider} instead.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ImperativeBulkhead<Void, Object> bulkhead = new ImperativeBulkhead<>(cfg, strategy);
 * ImperativeRetry<Void, Object>    retry    = new ImperativeRetry<>(retryCfg);
 *
 * @Aspect
 * public class ResilienceAspect extends AbstractPipelineAspect {
 *     public ResilienceAspect() {
 *         super(List.of(
 *             new ElementLayerProvider(bulkhead, 10),
 *             new ElementLayerProvider(retry, 20)
 *         ));
 *     }
 *
 *     @Around("@annotation(Resilient)")
 *     public Object around(ProceedingJoinPoint pjp) throws Throwable {
 *         return executeAround(pjp);
 *     }
 * }
 * }</pre>
 *
 * @since 0.8.0
 * @see AsyncElementLayerProvider
 */
public final class ElementLayerProvider implements AspectLayerProvider<Object> {

    private final InqElement element;
    private final LayerAction<Void, Object> layerAction;
    private final int order;
    private final String layerName;

    /**
     * Creates a sync layer provider from the given resilience element.
     *
     * <p>The intersection type bound {@code <E extends InqElement &
     * InqDecorator<Void, Object>>} guarantees at compile time that the
     * element provides both identity (name, type) and sync around-advice.</p>
     *
     * @param element the resilience element to adapt
     * @param order   the pipeline priority (lower = outermost wrapper)
     * @param <E>     intersection of {@link InqElement} and
     *                {@link InqDecorator InqDecorator&lt;Void, Object&gt;}
     * @throws NullPointerException if element is null
     */
    public <E extends InqElement & InqDecorator<Void, Object>> ElementLayerProvider(
            E element, int order) {
        Objects.requireNonNull(element, "Element must not be null");
        this.element = element;
        this.layerAction = element::execute;
        this.order = order;
        this.layerName = element.getElementType().name()
                + "(" + element.getName() + ")";
    }

    @Override
    public String layerName() {
        return layerName;
    }

    @Override
    public int order() {
        return order;
    }

    /**
     * Returns the element's synchronous around-advice as a {@link LayerAction}.
     *
     * <p>The method reference is captured once at construction time — no
     * per-call allocation.</p>
     */
    @Override
    public LayerAction<Void, Object> layerAction() {
        return layerAction;
    }

    /**
     * Returns {@code true} for synchronous methods only.
     *
     * <p>Methods returning {@link CompletionStage} are excluded — use
     * {@link AsyncElementLayerProvider} for those.</p>
     *
     * @param method the service method being invoked
     * @return {@code true} if the method does <em>not</em> return a
     *         {@link CompletionStage}
     */
    @Override
    public boolean canHandle(Method method) {
        return !CompletionStage.class.isAssignableFrom(method.getReturnType());
    }

    /**
     * Returns the underlying resilience element.
     *
     * <p>Useful for diagnostics, metrics polling, or runtime reconfiguration.</p>
     *
     * @return the wrapped element, never {@code null}
     */
    public InqElement element() {
        return element;
    }
}
